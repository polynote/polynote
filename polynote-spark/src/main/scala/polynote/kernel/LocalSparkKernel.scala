package polynote.kernel
import java.io.File
import java.nio.file.{FileSystems, Files}
import java.util.concurrent.{Executors, ThreadFactory}
import java.util.concurrent.atomic.AtomicInteger

import cats.effect.concurrent.Ref
import cats.instances.list._
import cats.syntax.traverse._
import fs2.concurrent.SignallingRef
import org.apache.spark.SparkEnv
import org.apache.spark.sql.SparkSession
import polynote.buildinfo.BuildInfo
import polynote.config.PolynoteConfig
import polynote.kernel.dependency.CoursierFetcher
import polynote.kernel.environment.{Config, CurrentNotebook, CurrentTask, Env, InterpreterEnvironment}
import polynote.kernel.interpreter.scal.{ScalaInterpreter, ScalaSparkInterpreter}
import polynote.kernel.interpreter.{Interpreter, State}
import polynote.kernel.logging.Logging
import polynote.kernel.util.{RefMap, pathOf}
import polynote.messages.{CellID, NotebookConfig, TinyList}
import polynote.runtime.spark.reprs.SparkReprsOf
import zio.blocking.{Blocking, effectBlocking}
import zio.clock.Clock
import zio.internal.Executor
import zio.{Task, RIO, ZIO}
import zio.interop.catz._
import zio.system.{env, property}

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.reflect.internal.util.AbstractFileClassLoader
import scala.reflect.io.PlainDirectory
import scala.tools.nsc.Settings
import scala.tools.nsc.io.Directory

// TODO: this class may not even be necessary
class LocalSparkKernel private[kernel] (
  compilerProvider: ScalaCompiler.Provider,
  sparkSession: SparkSession,
  interpreterState: Ref[Task, State],
  interpreters: RefMap[String, Interpreter],
  busyState: SignallingRef[Task, KernelBusyState]
) extends LocalKernel(compilerProvider, interpreterState, interpreters, busyState) {

  override def info(): TaskG[KernelInfo] = super.info().map {
    info => sparkSession.sparkContext.uiWebUrl match {
      case Some(url) => info + ("Spark Web UI:" -> s"""<a href="$url" target="_blank">$url</a>""")
      case None => info
    }
  }

  override protected def chooseInterpreterFactory(factories: List[Interpreter.Factory]): ZIO[Any, Unit, Interpreter.Factory] =
    ZIO.fromOption(factories.headOption)

}

class LocalSparkKernelFactory extends Kernel.Factory.LocalService {
  import LocalSparkKernel.kernelCounter

  // all the JARs in Spark's classpath. I don't think this is actually needed.
  private def sparkDistClasspath = env("SPARK_DIST_CLASSPATH").orDie.get.flatMap {
    cp => cp.split(File.pathSeparator).toList.map {
      filename =>
        val file = new File(filename)
        file.getName match {
          case "*" | "*.jar" =>
            effectBlocking {
              if (file.getParentFile.exists())
                Files.newDirectoryStream(file.getParentFile.toPath, file.getName).iterator().asScala.toList.map(_.toFile)
              else
                Nil
            }.orDie
          case _ =>
            effectBlocking(if (file.exists()) List(file) else Nil).orDie
        }
    }.sequence.map(_.flatten)
  }

  private def sparkClasspath = env("SPARK_HOME").orDie.get.flatMap {
    sparkHome =>
      effectBlocking {
        val homeFile = new File(sparkHome)
        if (homeFile.exists()) {
          val jarsPath = homeFile.toPath.resolve("jars")
          Files.newDirectoryStream(jarsPath, "*.jar").iterator().asScala.toList.map(_.toFile)
        } else Nil
      }.orDie
  }

  private def systemClasspath =
    property("java.class.path").orDie.get
      .map(_.split(File.pathSeparator).toList.map(new File(_)))

  private def updateSettings(settings: Settings): Settings = {
    settings.outputDirs.setSingleOutput(new PlainDirectory(new Directory(org.apache.spark.repl.Main.outputDir)))
    settings
  }

  def apply(): RIO[BaseEnv with GlobalEnv with CellEnv, Kernel] = for {
    scalaDeps        <- CoursierFetcher.fetch("scala")
    (main, transitive) = scalaDeps.partition(_._1)
    sparkRuntimeJar   = new File(pathOf(classOf[SparkReprsOf[_]]).getPath)
    sparkClasspath   <- (sparkClasspath orElse systemClasspath).option.map(_.getOrElse(Nil))
    _                <- Logging.info(s"Using spark classpath: ${sparkClasspath.mkString(":")}")
    sparkJars         = (sparkRuntimeJar :: ScalaCompiler.requiredPolynotePaths).map(f => f.toString -> f) ::: scalaDeps.map { case (_, uri, file) => (uri, file) }
    compiler         <- ScalaCompiler.provider(main.map(_._3), sparkRuntimeJar :: transitive.map(_._3) ::: sparkClasspath, updateSettings)
    classLoader      <- compiler.scalaCompiler.classLoader
    session          <- startSparkSession(sparkJars, classLoader)
    notebookPackage   = s"$$notebook$$${kernelCounter.getAndIncrement()}"
    busyState        <- SignallingRef[Task, KernelBusyState](KernelBusyState(busy = true, alive = true))
    interpreters     <- RefMap.empty[String, Interpreter]
    scalaInterpreter <- interpreters.getOrCreate("scala")(ScalaSparkInterpreter().provideSomeM(Env.enrich[Blocking](compiler)))
    interpState      <- Ref[Task].of[State](State.predef(State.Root, State.Root))
  } yield new LocalSparkKernel(compiler, session, interpState, interpreters, busyState)

  private def startSparkSession(deps: List[(String, File)], classLoader: ClassLoader): RIO[BaseEnv with GlobalEnv with CellEnv, SparkSession] = {

    // TODO: config option for using downloaded deps vs. giving the urls
    //       for now we'll just give Spark the urls to the deps
    val jars = deps.map(_._1)

    /**
      * We create a dedicated [[Executor]] for starting Spark, so that its context classloader can be fixed to the
      * notebook's class loader. This is necessary so that classes defined by the notebook (and dependencies) can be
      * found by Spark's serialization machinery.
      */
    def mkExecutor(): RIO[Blocking, Executor] = effectBlocking {
      val threadFactory = new ThreadFactory {
        def newThread(r: Runnable): Thread = {
          val thread = new Thread(r)
          thread.setName("Spark session")
          thread.setDaemon(true)
          thread.setContextClassLoader(classLoader)
          thread
        }
      }

      Executor.fromExecutionContext(2048)(ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor(threadFactory)))
    }

    def mkSpark(
      sparkConfig: Map[String, String]
    ): RIO[Blocking with Config with Logging, SparkSession] = ZIO {
      val outputPath = org.apache.spark.repl.Main.outputDir.toPath
      val conf = org.apache.spark.repl.Main.conf

      sparkConfig.foreach {
        case (k, v) => conf.set(k, v)
      }

      conf.setIfMissing("spark.master", "local[*]")
      if (conf.get("spark.master") == "local[*]") {
        conf.set("spark.driver.host", "127.0.0.1")
      }

      conf
        .setJars(jars)
        .set("spark.repl.class.outputDir", outputPath.toString)
        .setAppName(s"Polynote ${BuildInfo.version} session")

      org.apache.spark.repl.Main.createSparkSession()
    }

    /**
      *  If the session was already started by a different notebook, then it doesn't have our dependencies; inject them.
      *  Note that this will probably end in disaster anyhow – Spark was started with another notebook's classloader,
      *  and we can't even access that one. Spark kernels should really be run in a subprocess, but we should do our
      *  best to support it either way.
      */
    def ensureJars(session: SparkSession): ZIO[Logging, Nothing, Unit] = {
      val existingJars = session.conf.get("spark.jars").split(',').map(_.trim).filter(_.nonEmpty)
      def addJar(jar: String): ZIO[Logging, Nothing, Unit] =
        ZIO(session.sparkContext.addJar(jar))
          .catchAll(Logging.error(s"Unable to add dependency $jar to spark", _))

      existingJars.diff(jars).toList.map(addJar).sequence.unit
    }

    def attachListener(session: SparkSession): RIO[BaseEnv with TaskManager, Unit] = for {
      taskManager <- TaskManager.access
      zioRuntime  <- ZIO.runtime[Blocking with Clock with Logging]
      _           <- ZIO(session.sparkContext.addSparkListener(new KernelListener(taskManager, session, zioRuntime)))
    } yield ()

    TaskManager.run("Spark", "Spark", "Starting Spark session") {
      for {
        config         <- Config.access
        notebookConfig <- CurrentNotebook.config
        executor       <- mkExecutor()
        session        <- mkSpark(config.spark ++ notebookConfig.sparkConfig.getOrElse(Map.empty)).lock(executor)
        _              <- ensureJars(session).lock(executor)
        _              <- ZIO(SparkEnv.get.serializer.setDefaultClassLoader(classLoader)).lock(executor)
        _              <- attachListener(session)
      } yield session
    }
  }
}

object LocalSparkKernel extends LocalSparkKernelFactory {
  private[kernel] val kernelCounter = new AtomicInteger(0)
}
