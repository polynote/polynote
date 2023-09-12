package polynote.kernel
import java.io.File
import java.nio.file.{FileSystems, Files}
import java.util.concurrent.{Executors, ThreadFactory}
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import org.apache.spark.SparkEnv
import org.apache.spark.sql.SparkSession
import polynote.buildinfo.BuildInfo
import polynote.config.{PolynoteConfig, SparkConfig}
import polynote.kernel.dependency.{Artifact, CoursierFetcher}
import polynote.kernel.environment.{Config, CurrentNotebook, CurrentTask, Env}
import polynote.kernel.interpreter.scal.{ScalaInterpreter, ScalaSparkInterpreter}
import polynote.kernel.interpreter.{Interpreter, InterpreterState, State}
import polynote.kernel.logging.Logging
import polynote.kernel.task.TaskManager
import polynote.kernel.util.{RefMap, pathOf}
import polynote.messages.{CellID, NotebookConfig, TinyList}
import polynote.runtime.spark.reprs.SparkReprsOf
import zio.blocking.{Blocking, effectBlocking}
import zio.clock.Clock
import zio.internal.Executor
import zio.{Promise, RIO, Task, URIO, ZIO, ZLayer}
import zio.system.System
import zio.stream.SubscriptionRef
import zio.system.{env, property}

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.reflect.internal.util.AbstractFileClassLoader
import scala.reflect.io.PlainDirectory
import scala.tools.nsc.Settings
import scala.tools.nsc.io.Directory

// TODO: this class may not even be necessary
class LocalSparkKernel private[kernel] (
  compilerProvider: ScalaCompiler,
  sparkSession: SparkSession,
  interpreterState: InterpreterState.Service,
  interpreters: RefMap[String, Interpreter],
  busyState: SubscriptionRef[KernelBusyState],
  closed: Promise[Throwable, Unit]
) extends LocalKernel(compilerProvider, interpreterState, interpreters, busyState, closed) {

  override def info(): TaskG[KernelInfo] = super.info().map {
    info => sparkSession.sparkContext.uiWebUrl match {
      case Some(url) => info + ("Spark Web UI:" -> s"""<a href="$url" target="_blank">$url</a>""")
      case None => info
    }
  }

  override protected def chooseInterpreterFactory(factories: List[Interpreter.Factory]): ZIO[Any, Option[Nothing], Interpreter.Factory] =
    ZIO.fromOption(factories.headOption)

  override def shutdown(): Task[Unit] = super.shutdown() *> ZIO(sparkSession.stop())
}

class LocalSparkKernelFactory extends Kernel.Factory.LocalService {

  // all the JARs in Spark's classpath. I don't think this is actually needed.
  private def sparkDistClasspath: URIO[Blocking with Config with System, List[File]] = env("SPARK_DIST_CLASSPATH").orDie.get.flatMap {
    cp =>
      Config.access.flatMap {
        config =>
          val requiredFilter = Pattern.compile("hadoop-(common|mapreduce)").asPredicate()
          val filter = config.spark.flatMap(_.distClasspathFilter) match {
            case None          => requiredFilter
            case Some(pattern) => requiredFilter.or(pattern.asPredicate())
          }

          ZIO.foreach(cp.split(File.pathSeparator).toList) {
            filepath =>
              val file = new File(filepath)
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
          }.map(_.flatten).map {
            expandedFiles =>
              expandedFiles.filter {
                path =>
                  val pathStr = path.getAbsolutePath
                  filter.test(pathStr) && pathStr.endsWith(".jar") && !pathStr.endsWith("-sources.jar")
              }
          }

      }
  }.orElseSucceed(Nil)

  private def sparkClasspath: ZIO[Blocking with Logging with Config with System, Option[Nothing], List[File]] =
    env("SPARK_HOME").orDie.get.flatMap {
      sparkHome =>
        for {
          fromSparkDist <- sparkDistClasspath
          _             <- ZIO.when(fromSparkDist.nonEmpty)(Logging.info(s"Adding these paths from SPARK_DIST_CLASSPATH: $fromSparkDist"))
          fromSparkJars <- effectBlocking {
            val homeFile = new File(sparkHome)
            if (homeFile.exists()) {
              val jarsPath = homeFile.toPath.resolve("jars")
              Files.newDirectoryStream(jarsPath, "*.jar").iterator().asScala.toList.map(_.toFile)
            } else Nil
          }.orDie
        } yield (fromSparkDist ++ fromSparkJars).distinct
    }

  private def systemClasspath: ZIO[System, Option[Nothing], List[File]] =
    property("java.class.path").orDie.get
      .map(_.split(File.pathSeparator).toList.map(new File(_)))

  private def updateSettings(settings: Settings): Settings = {
    settings.outputDirs.setSingleOutput(new PlainDirectory(new Directory(org.apache.spark.repl.Main.outputDir)))
    settings
  }

  def apply(): RIO[BaseEnv with GlobalEnv with CellEnv, Kernel] = for {
    scalaDeps        <- CoursierFetcher.fetch("scala")
    sparkRuntimeJar   = new File(pathOf(classOf[SparkReprsOf[_]]).getPath)
    sparkClasspath   <- (sparkClasspath orElse systemClasspath).option.map(_.getOrElse(Nil))
    _                <- Logging.info(s"Using spark classpath: ${sparkClasspath.mkString(":")}")
    sparkJars         = (sparkRuntimeJar :: ScalaCompiler.requiredPolynotePaths).map(f => f.toString -> f) ::: scalaDeps.map {a => (a.url, a.file) }
    compiler         <- ScalaCompiler(Artifact(false, sparkRuntimeJar.toURI.toString, sparkRuntimeJar, None) :: scalaDeps, sparkClasspath, updateSettings _)
    classLoader       = compiler.classLoader
    session          <- startSparkSession(sparkJars, classLoader)
    busyState        <- SubscriptionRef.make(KernelBusyState(busy = true, alive = true))
    interpreters     <- RefMap.empty[String, Interpreter]
    scalaInterpreter <- interpreters.getOrCreate("scala")(ScalaSparkInterpreter().provideSomeLayer[BaseEnv with Config with TaskManager](ZLayer.succeed(compiler)))
    interpState      <- InterpreterState.access
    closed           <- Promise.make[Throwable, Unit]
  } yield new LocalSparkKernel(compiler, session, interpState, interpreters, busyState, closed)

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
      sparkConfig: Map[String, String],
      notebookPath: String
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
        .setIfMissing("spark.app.name", s"Polynote ${BuildInfo.version}: $notebookPath")

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

      ZIO.foreach_(existingJars.diff(jars))(addJar)
    }

    def attachListener(session: SparkSession): RIO[BaseEnv with TaskManager, Unit] = for {
      taskManager <- TaskManager.access
      zioRuntime  <- ZIO.runtime[Blocking with Clock with Logging]
      _           <- ZIO(session.sparkContext.addSparkListener(new KernelListener(taskManager, session, zioRuntime)))
    } yield ()

    def interpreterSparkConfigs = for {
      factories <- Interpreter.Factories.access
      configs   <- ZIO.foldLeft(factories.values.map(_.head))(Map.empty[String, String])((configs, factory) => factory.sparkConfig(configs))
    } yield configs

    TaskManager.run("Spark", "Spark", "Starting Spark session") {
      for {
        config             <- Config.access
        notebookConfig     <- CurrentNotebook.config
        path               <- CurrentNotebook.path
        executor           <- mkExecutor()
        interpreterConfigs <- interpreterSparkConfigs
        serverConfigs       = config.spark.map(SparkConfig.toMap).getOrElse(Map.empty)
        notebookConfigs     = notebookConfig.sparkConfig.getOrElse(Map.empty)
        sparkConfigs        = interpreterConfigs ++ serverConfigs ++ notebookConfigs
        session            <- mkSpark(sparkConfigs, path).lock(executor)
        _                  <- ensureJars(session).lock(executor)
        _                  <- ZIO(SparkEnv.get.serializer.setDefaultClassLoader(classLoader)).lock(executor)
        _                  <- attachListener(session)
      } yield session
    }
  }
}

object LocalSparkKernel extends LocalSparkKernelFactory
