package polynote.kernel
import java.io.File
import java.nio.file.{FileSystems, Files}
import java.util.concurrent.atomic.AtomicInteger

import cats.effect.concurrent.Ref
import cats.instances.list._
import cats.syntax.traverse._
import fs2.concurrent.SignallingRef
import org.apache.spark.sql.SparkSession
import polynote.buildinfo.BuildInfo
import polynote.config.PolynoteConfig
import polynote.kernel.dependency.CoursierFetcher
import polynote.kernel.environment.{Config, CurrentNotebook, CurrentTask, InterpreterEnvironment}
import polynote.kernel.interpreter.scal.{ScalaInterpreter, ScalaSparkInterpreter}
import polynote.kernel.interpreter.{Interpreter, State}
import polynote.kernel.logging.Logging
import polynote.kernel.util.{RefMap, pathOf}
import polynote.messages.{CellID, NotebookConfig, TinyList}
import polynote.runtime.spark.reprs.SparkReprsOf
import zio.blocking.{Blocking, effectBlocking}
import zio.clock.Clock
import zio.{Task, TaskR, ZIO}
import zio.interop.catz._
import zio.system.{env, property}

import scala.collection.JavaConverters._
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

class LocalSparkKernelFactory extends Kernel.Factory.Service {
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

  def apply(): TaskR[BaseEnv with GlobalEnv with CellEnv, Kernel] = for {
    scalaDeps             <- CoursierFetcher.fetch("scala")
    sparkRuntimeJar        = new File(pathOf(classOf[SparkReprsOf[_]]).getPath)
    sparkClasspath        <- (sparkClasspath orElse systemClasspath).option.map(_.getOrElse(Nil))
    _                     <- Logging.info(s"Using spark classpath: ${sparkClasspath.mkString(":")}")
    settings               = ScalaCompiler.defaultSettings(new Settings(), sparkRuntimeJar :: scalaDeps.map(_._2) ::: sparkClasspath)
    sparkJars              = (sparkRuntimeJar :: ScalaCompiler.requiredPaths).map(f => f.toString -> f) ::: scalaDeps
    sessionAndClassLoader <- startSparkSession(sparkJars, settings)
    (session, classLoader) = sessionAndClassLoader
    notebookPackage        = s"$$notebook$$${kernelCounter.getAndIncrement()}"
    compiler              <- ScalaCompiler(settings, ZIO.succeed(classLoader), notebookPackage).map(ScalaCompiler.Provider.of)
    busyState             <- SignallingRef[Task, KernelBusyState](KernelBusyState(busy = true, alive = true))
    interpreters          <- RefMap.empty[String, Interpreter]
    scalaInterpreter      <- interpreters.getOrCreate("scala")(ScalaSparkInterpreter().provide(compiler))
    interpState           <- Ref[Task].of[State](State.predef(State.Root, State.Root))
  } yield new LocalSparkKernel(compiler, session, interpState, interpreters, busyState)

  private def startSparkSession(deps: List[(String, File)], settings: Settings): TaskR[BaseEnv with GlobalEnv with CellEnv, (SparkSession, AbstractFileClassLoader)] = {
    def mkSpark(
      notebookConfig: NotebookConfig,
      settings: Settings
    ): TaskR[Blocking with Config with Logging, (SparkSession, AbstractFileClassLoader)] = Config.access.flatMap {
      config =>
        effectBlocking {
          val outputPath = org.apache.spark.repl.Main.outputDir.toPath
          val conf = org.apache.spark.repl.Main.conf
          val sparkConfig = config.spark ++ notebookConfig.sparkConfig.getOrElse(Map.empty)
  
          sparkConfig.foreach {
            case (k, v) => conf.set(k, v)
          }
  
          conf.setIfMissing("spark.master", "local[*]")
          if (conf.get("spark.master") == "local[*]") {
            conf.set("spark.driver.host", "127.0.0.1")
          }
  
          // TODO: config option for using downloaded deps vs. giving the urls
          //       for now we'll just give Spark the urls to the deps
          val jars = deps.map(_._1)
          conf
            .setJars(jars)
            .set("spark.repl.class.outputDir", outputPath.toString)
            .setAppName(s"Polynote ${BuildInfo.version} session")
  
          val outputDir = new PlainDirectory(new Directory(outputPath.toFile))
          settings.outputDirs.setSingleOutput(outputDir)
  
          ScalaCompiler.makeClassLoader(settings).flatMap {
            classLoader => effectBlocking {
              val session = classLoader.asContext {
                org.apache.spark.repl.Main.createSparkSession()
              }
              (session, classLoader)
            }.tap {
              case (session, _) =>
                // if the session was already started by a different notebook, then it doesn't have our dependencies
                val existingJars = session.conf.get("spark.jars").split(',').map(_.trim).filter(_.nonEmpty)
                existingJars.diff(jars).toList.map {
                  jar => effectBlocking(session.sparkContext.addJar(jar)).catchAll(Logging.error(s"Unable to add dependency $jar to spark", _))
                }.sequence
            }
          }
      }.flatten
    }

    def attachListener(session: SparkSession): TaskR[BaseEnv with TaskManager, Unit] = for {
      taskManager <- TaskManager.access
      zioRuntime  <- ZIO.runtime[Blocking with Clock]
      _           <- ZIO(session.sparkContext.addSparkListener(new KernelListener(taskManager, session, zioRuntime)))
    } yield ()

    TaskManager.run("Spark", "Spark", "Starting Spark session") {
      for {
        notebookConfig        <- CurrentNotebook.config
        sessionAndClassLoader <- mkSpark(notebookConfig, settings)
        (session, _)           = sessionAndClassLoader
        _                     <- attachListener(session)
      } yield sessionAndClassLoader
    }
  }
}

object LocalSparkKernel extends LocalSparkKernelFactory {
  private[kernel] val kernelCounter = new AtomicInteger(0)
}
