package polynote.server
import java.io.File

import cats.effect.{ContextShift, ExitCode, IO, Timer}
import cats.implicits._
import polynote.config.PolynoteConfig
import polynote.kernel.dependency.{DependencyManagerFactory, DependencyProvider}
import polynote.kernel.lang.LanguageInterpreter
import polynote.kernel.lang.python.{PySparkVirtualEnvDependencyProvider, PySparkVirtualEnvManager}
import polynote.kernel.remote.{RemoteSparkKernel, SocketTransport, Transport}
import polynote.kernel.util.{PlainServerCommand, Publish, SparkSubmitCommand}
import polynote.kernel.{KernelAPI, KernelStatusUpdate, PolyKernel, SparkPolyKernel}
import polynote.messages.Notebook

import scala.reflect.io.AbstractFile
import scala.tools.nsc.Settings

object SparkServer extends Server {
  override def parseArgs(args: List[String]): IO[ServerArgs] = args match {
    case "--printCommand" :: rest => super.parseArgs(rest).map(SparkServerArgs(true, _))
    case rest => super.parseArgs(rest).map(SparkServerArgs(false, _))
  }

  override def run(args: List[String]): IO[ExitCode] = getConfigs(args).flatMap {
    case (SparkServerArgs(true, _, _), config) =>
      IO {
        // don't launch with SparkSubmit when remote.
        if (config.spark.get("polynote.kernel.remote") contains "true") {
          val cmd = PlainServerCommand(config.spark).map {
            str => if (str contains " ") s""""$str"""" else str
          }.mkString(" ")
          // TODO: this should be something other than `SparkSubmit`
          println(s"SparkSubmit: $cmd") // must be println because this stdout is what gets parsed by callers.
        } else {
          val cmd = SparkSubmitCommand(config.spark).map {
            str => if (str contains " ") s""""$str"""" else str
          }.mkString(" ")
          println(s"SparkSubmit: $cmd") // must be println because this stdout is what gets parsed by callers.
        }
      } *> IO.pure(ExitCode.Success)
    case _ => super.run(args)
  }

  // visible for testing
  override protected[server] def kernelFactory: KernelFactory[IO] = new SparkKernelFactory
}

case class SparkServerArgs(
  printCommand: Boolean,
  configFile: File,
  watchUI: Boolean
) extends ServerArgs

object SparkServerArgs {
  def apply(printCommand: Boolean, args: ServerArgs): SparkServerArgs = SparkServerArgs(printCommand, args.configFile, args.watchUI)
}

class SparkKernelFactory(implicit
  contextShift: ContextShift[IO],
  timer: Timer[IO]
) extends IOKernelFactory {
  override protected def mkKernel(
    getNotebook: () => IO[Notebook],
    deps: Map[String, DependencyProvider],
    subKernels: Map[String, LanguageInterpreter.Factory[IO]],
    statusUpdates: Publish[IO, KernelStatusUpdate],
    config: PolynoteConfig,
    extraClassPath: List[File],
    settings: Settings,
    outputDir: AbstractFile,
    parentClassLoader: ClassLoader
  ): IO[PolyKernel] = IO.pure(SparkPolyKernel(getNotebook, deps, subKernels, statusUpdates, extraClassPath, settings, parentClassLoader, config))

  override def launchKernel(
    getNotebook: () => IO[Notebook],
    statusUpdates: Publish[IO, KernelStatusUpdate],
    polynoteConfig: PolynoteConfig
  ): IO[KernelAPI[IO]] = if (polynoteConfig.spark.get("polynote.kernel.remote") contains "true") {
    new SparkRemoteKernelFactory(new SocketTransport).launchKernel(getNotebook, statusUpdates, polynoteConfig)
  } else {
    super.launchKernel(getNotebook, statusUpdates, polynoteConfig)
  }
}

class SparkRemoteKernelFactory(
  transport: Transport[_])(implicit
  contextShift: ContextShift[IO],
  timer: Timer[IO]
) extends KernelFactory[IO] {
  def launchKernel(getNotebook: () => IO[Notebook], statusUpdates: Publish[IO, KernelStatusUpdate], config: PolynoteConfig): IO[KernelAPI[IO]] =
    RemoteSparkKernel(statusUpdates, getNotebook, config, transport)
}