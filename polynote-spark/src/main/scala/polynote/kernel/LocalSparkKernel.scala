package polynote.kernel
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

import cats.effect.concurrent.Ref
import cats.instances.list._
import cats.syntax.traverse._
import fs2.concurrent.SignallingRef
import org.apache.spark.sql.SparkSession
import polynote.buildinfo.BuildInfo
import polynote.config.PolynoteConfig
import polynote.kernel.dependency.ZIOCoursierFetcher
import polynote.kernel.environment.{Config, CurrentNotebook, CurrentTask, InterpreterEnvironment}
import polynote.kernel.interpreter.scal.ScalaInterpreter
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

import scala.reflect.internal.util.AbstractFileClassLoader
import scala.reflect.io.PlainDirectory
import scala.tools.nsc.Settings
import scala.tools.nsc.io.Directory

// TODO: this class may not even be necessary
class LocalSparkKernel private[kernel] (
  compilerProvider: ScalaCompiler.Provider,
  interpreterState: Ref[Task, State],
  interpreters: RefMap[String, Interpreter],
  busyState: SignallingRef[Task, KernelBusyState]
) extends LocalKernel(compilerProvider, interpreterState, interpreters, busyState)

class LocalSparkKernelFactory extends Kernel.Factory.Service {
  import LocalSparkKernel.kernelCounter
  
  def apply(): TaskR[BaseEnv with GlobalEnv with CellEnv, Kernel] = for {
    scalaDeps             <- ZIOCoursierFetcher.fetch("scala")
    sparkRuntimeJar        = pathOf(classOf[SparkReprsOf[_]])
    sparkClasspathOpt     <- (env("SPARK_DIST_CLASSPATH").orDie.get orElse property("java.class.path").orDie.get).option
    sparkClasspath         = sparkClasspathOpt.map(_.split(File.pathSeparatorChar).toList.map(new File(_))).getOrElse(Nil)
    settings               = ScalaCompiler.defaultSettings(new Settings(), scalaDeps.map(_._2) ::: sparkClasspath)
    sessionAndClassLoader <- startSparkSession(scalaDeps, settings)
    (session, classLoader) = sessionAndClassLoader
    notebookPackage        = s"$$notebook$$${kernelCounter.getAndIncrement()}"
    compiler              <- ScalaCompiler(settings, ZIO.succeed(classLoader), notebookPackage).map(ScalaCompiler.Provider.of)
    busyState             <- SignallingRef[Task, KernelBusyState](KernelBusyState(busy = true, alive = true))
    interpreters          <- RefMap.empty[String, Interpreter]
    scalaInterpreter      <- interpreters.getOrCreate("scala")(ScalaInterpreter.fromFactory().provide(compiler).flatten)
    interpState           <- Ref[Task].of[State](State.predef(State.Root, State.Root))
  } yield new LocalSparkKernel(compiler, interpState, interpreters, busyState)

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
