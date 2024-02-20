package polynote.kernel.remote

import java.io.{BufferedReader, File}
import java.net.{InetSocketAddress, URL}
import polynote.buildinfo.BuildInfo
import polynote.config.{PolynoteConfig, SparkConfig}
import polynote.kernel.{BaseEnv, Kernel, ScalaCompiler, remote}
import polynote.kernel.environment.{Config, CurrentNotebook}
import polynote.kernel.logging.Logging
import polynote.kernel.remote.SocketTransport.DeploySubprocess.DeployCommand
import polynote.kernel.util.{listFiles, pathOf}
import polynote.messages.NotebookConfig
import polynote.runtime.KernelRuntime
import zio.{RIO, URIO, ZIO, ZManaged}
import zio.blocking.effectBlocking

import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.atomic.AtomicReference

object DeploySparkSubmit extends DeployCommand {
  def parseQuotedArgs(str: String): List[String] = str.split('"').toList.sliding(2, 2).toList.flatMap {
    case nonQuoted :: quoted :: Nil => nonQuoted.split("\\s+").toList ::: quoted :: Nil
    case nonQuoted :: Nil => nonQuoted.split("\\s+").toList
    case _ => sys.error("impossible sliding state")
  }.map(_.trim).filterNot(_.isEmpty)

  def build(
    config: PolynoteConfig,
    nbConfig: NotebookConfig,
    notebookPath: String,
    classPath: Seq[URL],
    mainClass: String = classOf[RemoteKernelClient].getName,
    defaultJarLocation: String = getClass.getProtectionDomain.getCodeSource.getLocation.getPath,
    serverArgs: List[String] = Nil
  ): Seq[String] = {

    val versionConfig = nbConfig.scalaVersion.flatMap(scalaVersion => {
      nbConfig.sparkTemplate
        .map(_.versionConfigs)
        .flatMap(versionConfig => versionConfig.flatMap(_.find(_.versionNumber == scalaVersion)))
    })

    val sparkConfig = config.spark.map(_.properties).getOrElse(Map.empty) ++
      versionConfig.map(_.versionProperties).getOrElse(Map.empty) ++
      nbConfig.sparkTemplate.map(_.properties).getOrElse(Map.empty) ++
      nbConfig.sparkConfig.getOrElse(Map.empty)

    val sparkArgs = (sparkConfig - "sparkSubmitArgs" - "spark.driver.extraJavaOptions" - "spark.submit.deployMode" - "spark.driver.memory")
      .flatMap(kv => Seq("--conf", s"${kv._1}=${kv._2}"))

    val sparkSubmitArgs =
      nbConfig.sparkTemplate.flatMap(_.sparkSubmitArgs).toList.flatMap(parseQuotedArgs) ++
      versionConfig.flatMap(_.sparkSubmitArgs).toList.flatMap(parseQuotedArgs) ++
      sparkConfig.get("sparkSubmitArgs").toList.flatMap(parseQuotedArgs)

    val isRemote = sparkConfig.get("spark.submit.deployMode") contains "cluster"

    val allDriverOptions = {
      val all: List[String] = jvmArgs(nbConfig) ++
        sparkConfig.get("spark.driver.extraJavaOptions").toList ++
        asPropString(javaOptions)
      all mkString " "
    }

    val additionalJars = classPath.toList.filter(_.getFile.endsWith(".jar"))

    val appName = sparkConfig.getOrElse("spark.app.name", s"Polynote ${BuildInfo.version}: $notebookPath")

    val runtimeJarsFilter = raw"polynote-(spark-)?runtime".r

    val applicationJar = additionalJars.find(_.getPath.contains("polynote-spark-assembly")).map(_.getPath).getOrElse(defaultJarLocation)

    val jarsToAdd = additionalJars.filter(url => runtimeJarsFilter.findFirstMatchIn(url.getPath).nonEmpty)

    Seq("spark-submit", "--class", mainClass, "--name", appName) ++
      Seq("--driver-java-options", allDriverOptions) ++
      sparkConfig.get("spark.driver.memory").toList.flatMap(mem => List("--driver-memory", mem)) ++
      (if (isRemote) Seq("--deploy-mode", "cluster") else Nil) ++
      sparkSubmitArgs ++ Seq("--driver-class-path", classPath.map(_.getPath).mkString(File.pathSeparator)) ++
      (if (jarsToAdd.nonEmpty) Seq("--jars", jarsToAdd.mkString(",")) else Nil) ++
      sparkArgs ++ Seq(applicationJar) ++ serverArgs
  }

  override def apply(serverAddress: InetSocketAddress, classPath: Seq[Path]): RIO[Config with CurrentNotebook, Seq[String]] = for {
    config   <- Config.access
    nbConfig <- CurrentNotebook.config
    path     <- CurrentNotebook.path
  } yield build(
    config,
    nbConfig,
    path,
    classPath.map(_.toUri.toURL),
    serverArgs =
      "--address" :: serverAddress.getAddress.getHostAddress ::
      "--port" :: serverAddress.getPort.toString ::
      "--kernelFactory" :: "polynote.kernel.LocalSparkKernelFactory" ::
      Nil
  )

  // we assume spark-submit is going to use the same scala version every time and memoize its output
  private val detectedVersion = new AtomicReference[Option[Option[String]]](None)

  private[remote] val detectFromSparkSubmit: ZIO[BaseEnv, Nothing, Option[String]] = {
    def process = effectBlocking(new ProcessBuilder("spark-submit", "--version").start())
      .toManaged {
        process => effectBlocking(process.waitFor()).ignore.ensuring {
          effectBlocking(process.destroyForcibly()).ignore.repeatUntil(_ => !process.isAlive)
        }
      }

    def processOutput = for {
      process       <- process
        processOutput <- ZManaged.fromAutoCloseable(ZIO(process.getErrorStream))
        outputSource  <- ZIO.effectTotal(scala.io.Source.fromInputStream(processOutput)).toManaged(src => ZIO.effectTotal(src.close()))
    } yield outputSource

    val findScalaVersion = raw"Using Scala(?: version)? (\d\.\d+)".r

    processOutput.use {
      src => effectBlocking(src.getLines().toSeq).map {
        lines => lines.map(findScalaVersion.findFirstMatchIn).collectFirst {
          case Some(m) => m.group(1)
        }
      }
    }.catchAll {
      err => Logging.warn(s"Failed to detect Scala version from spark-submit", err).as(None)
    }
  }

  private case object NoSparkHome extends Throwable("No SPARK_HOME is available")

  private[remote] val detectFromSparkHome: URIO[BaseEnv, Option[String]] = {
    val sparkJar = raw"scala-library-(\d\.\d+).*\.jar".r
    for {
      sparkHome <- zio.system.env("SPARK_HOME").someOrFail(NoSparkHome)
      jarsDir   <- ZIO(Paths.get(sparkHome, "jars"))
      jars      <- listFiles(jarsDir)
    } yield jars.view.map(_.getFileName.toString).collectFirst {
      case sparkJar(ver) => ver
    }
  }.tapError {
    err =>
      Logging.warn("Unable to find SPARK_HOME", err)
  }.orElse(ZIO.none)

  override val detectScalaVersion: URIO[BaseEnv, Option[String]] =
    ZIO.effectTotal(detectedVersion.get).flatMap {
      case Some(v) => ZIO.succeed(v)
      case None    =>
        (detectFromSparkHome.some orElse detectFromSparkSubmit.some)
          .option
          .tap(v => ZIO.effectTotal(detectedVersion.set(Some(v))))
    }
}

