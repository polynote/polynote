package polynote.kernel
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

import cats.effect.concurrent.Ref
import fs2.concurrent.SignallingRef
import org.apache.spark.sql.SparkSession
import polynote.buildinfo.BuildInfo
import polynote.config.PolynoteConfig
import polynote.kernel.dependency.ZIOCoursierFetcher
import polynote.kernel.environment.{Config, CurrentNotebook, CurrentTask, InterpreterEnvironment}
import polynote.kernel.interpreter.scal.ScalaInterpreter
import polynote.kernel.interpreter.{Interpreter, State}
import polynote.kernel.util.RefMap
import polynote.messages.{CellID, NotebookConfig, TinyList}
import polynote.runtime.spark.reprs.SparkReprsOf
import zio.blocking.Blocking
import zio.clock.Clock
import zio.{Task, TaskR, ZIO}
import zio.interop.catz._
import zio.system.{env, property}

import scala.reflect.internal.util.AbstractFileClassLoader
import scala.reflect.io.PlainDirectory
import scala.tools.nsc.Settings
import scala.tools.nsc.io.Directory

class LocalSparkKernel private[kernel] (
  compilerProvider: ScalaCompiler.Provider,
  interpreterState: Ref[Task, State],
  interpreters: RefMap[String, Interpreter],
  busyState: SignallingRef[Task, KernelBusyState],
  predefStateId: AtomicInteger
) extends LocalKernel(compilerProvider, interpreterState, interpreters, busyState, predefStateId) {
  override def init(): TaskR[BaseEnv with GlobalEnv with CellEnv, Unit] = super.init()
}

object LocalSparkKernel extends Kernel.Factory.Service {

  private val sparkPredef =
    s"""import org.apache.spark.sql.SparkSession
       |@transient val spark: SparkSession = if (org.apache.spark.repl.Main.sparkSession != null) {
       |            org.apache.spark.repl.Main.sparkSession
       |          } else {
       |            org.apache.spark.repl.Main.createSparkSession()
       |          }
       |import org.apache.spark.sql.{DataFrame, Dataset}
       |import spark.implicits._
       |import org.apache.spark.sql.functions._
       |""".stripMargin

  def apply(): TaskR[BaseEnv with GlobalEnv with CellEnv, Kernel] = for {
    scalaDeps             <- ZIOCoursierFetcher.fetch("scala")
    sparkClasspathOpt     <- (env("SPARK_DIST_CLASSPATH").orDie.get orElse property("java.class.path").orDie.get).option
    sparkClasspath         = sparkClasspathOpt.map(_.split(File.pathSeparatorChar).toList.map(new File(_))).getOrElse(Nil)
    settings               = ScalaCompiler.defaultSettings(new Settings(), scalaDeps.map(_._2) ::: sparkClasspath)
    sessionAndClassLoader <- startSparkSession(scalaDeps, settings)
    (session, classLoader) = sessionAndClassLoader
    compiler              <- ScalaCompiler(settings, ZIO.succeed(classLoader)).map(ScalaCompiler.Provider.of)
    busyState             <- SignallingRef[Task, KernelBusyState](KernelBusyState(busy = true, alive = true))
    interpreters          <- RefMap.empty[String, Interpreter]
    scalaInterpreter      <- interpreters.getOrCreate("scala")(ScalaInterpreter.fromFactory().provide(compiler).flatten)
    predefStateId          = new AtomicInteger(-1)
    initialState          <- TaskManager.runR[BaseEnv with GlobalEnv with CellEnv]("SparkPredef", "Spark predef")(scalaInterpreter.runId(predefStateId.getAndDecrement(), sparkPredef))
    interpState           <- Ref[Task].of[State](initialState)
  } yield new LocalKernel(compiler, interpState, interpreters, busyState, predefStateId)

  private def startSparkSession(deps: List[(String, File)], settings: Settings): TaskR[BaseEnv with GlobalEnv with CellEnv, (SparkSession, AbstractFileClassLoader)] = {
    def mkSpark(
      notebookConfig: NotebookConfig,
      settings: Settings
    ): TaskR[Blocking with Config, (SparkSession, AbstractFileClassLoader)] = Config.access.flatMap {
      config =>
        zio.blocking.effectBlocking {
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
        conf
          .setJars(deps.map(_._1))
          .set("spark.repl.class.outputDir", outputPath.toString)
          .setAppName(s"Polynote ${BuildInfo.version} session")

        val outputDir = new PlainDirectory(new Directory(outputPath.toFile))
        settings.outputDirs.setSingleOutput(outputDir)

        ScalaCompiler.makeClassLoader(settings).flatMap {
          classLoader => zio.blocking.effectBlocking {
            val session = classLoader.asContext {
              org.apache.spark.repl.Main.createSparkSession()
            }
            (session, classLoader)
          }
        }
      }.flatten
    }

    def attachListener(session: SparkSession): TaskR[BaseEnv with TaskManager, Unit] = for {
      taskManager <- TaskManager.access
      zioRuntime  <- ZIO.runtime[Blocking with Clock]
      _           <- ZIO(session.sparkContext.addSparkListener(new ZIOKernelListener(taskManager, session, zioRuntime)))
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
