package polynote.kernel.remote

import java.io.File
import java.net.{InetSocketAddress, URL}
import polynote.buildinfo.BuildInfo
import polynote.config.{PolynoteConfig, SparkConfig}
import polynote.kernel.{Kernel, ScalaCompiler, remote}
import polynote.kernel.environment.{Config, CurrentNotebook}
import polynote.kernel.remote.SocketTransport.DeploySubprocess.DeployCommand
import polynote.kernel.util.pathOf
import polynote.messages.NotebookConfig
import polynote.runtime.KernelRuntime
import zio.{RIO, ZIO}

import java.nio.file.Path

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
    jarLocation: String = getClass.getProtectionDomain.getCodeSource.getLocation.getPath,
    serverArgs: List[String] = Nil
  ): Seq[String] = {

    val sparkConfig = config.spark.map(_.properties).getOrElse(Map.empty) ++
      nbConfig.sparkTemplate.map(_.properties).getOrElse(Map.empty) ++
      nbConfig.sparkConfig.getOrElse(Map.empty)

    val sparkArgs = (sparkConfig - "sparkSubmitArgs" - "spark.driver.extraJavaOptions" - "spark.submit.deployMode" - "spark.driver.memory")
      .flatMap(kv => Seq("--conf", s"${kv._1}=${kv._2}"))

    val sparkSubmitArgs =
      nbConfig.sparkTemplate.flatMap(_.sparkSubmitArgs).toList.flatMap(parseQuotedArgs) ++
      sparkConfig.get("sparkSubmitArgs").toList.flatMap(parseQuotedArgs)

    val isRemote = sparkConfig.get("spark.submit.deployMode") contains "cluster"
    val libraryPath = List(sys.props.get("java.library.path"), sys.env.get("LD_LIBRARY_PATH"))
      .flatten
      .map(_.trim().stripPrefix(File.pathSeparator).stripSuffix(File.pathSeparator))
      .mkString(File.pathSeparator)

    val javaOptions = Map(
      "log4j.configuration" -> "log4j.properties",
      "java.library.path"   -> libraryPath
    )

    val allDriverOptions =
      sparkConfig.get("spark.driver.extraJavaOptions").toList ++
      javaOptions.toList.map {
        case (name, value) => s"-D$name=$value"
      } mkString " "

    val additionalJars = classPath.toList.filter(_.getFile.endsWith(".jar"))

    val appName = sparkConfig.getOrElse("spark.app.name", s"Polynote ${BuildInfo.version}: $notebookPath")

    Seq("spark-submit", "--class", mainClass, "--name", appName) ++
      Seq("--driver-java-options", allDriverOptions) ++
      sparkConfig.get("spark.driver.memory").toList.flatMap(mem => List("--driver-memory", mem)) ++
      (if (isRemote) Seq("--deploy-mode", "cluster") else Nil) ++
      sparkSubmitArgs ++ Seq("--jars", additionalJars.mkString(",")) ++
      sparkArgs ++ Seq(jarLocation) ++ serverArgs
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
}

