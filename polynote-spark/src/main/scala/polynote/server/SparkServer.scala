package polynote.server
import java.io.File

import cats.effect.{ContextShift, ExitCode, IO, Timer}
import org.apache.spark.sql.thief.ActiveSessionThief
import cats.implicits._
import polynote.config.PolynoteConfig
import polynote.kernel.dependency.DependencyFetcher
import polynote.kernel.{KernelAPI, KernelStatusUpdate, PolyKernel, SparkPolyKernel}
import polynote.kernel.lang.LanguageInterpreter
import polynote.kernel.remote.{RemoteSparkKernel, SocketTransport, Transport}
import polynote.kernel.util.{Publish, SparkSubmitCommand}
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
        val cmd = SparkSubmitCommand(config.spark).map {
          str => if (str contains " ") s""""$str"""" else str
        }.mkString(" ")
        logger.info(s"SparkSubmit: $cmd")
      } *> IO.pure(ExitCode.Success)
    case _ => super.run(args)
  }

  override protected def kernelFactory: KernelFactory[IO] =
    if (sys.env.get("POLYNOTE_REMOTE_KERNELS") contains "true")
      new SparkRemoteKernelFactory(new SocketTransport)
    else
      new SparkKernelFactory(dependencyFetchers = Map("scala" -> dependencyFetcher))
}

case class SparkServerArgs(
  printCommand: Boolean,
  configFile: File,
  watchUI: Boolean
) extends ServerArgs

object SparkServerArgs {
  def apply(printCommand: Boolean, args: ServerArgs): SparkServerArgs = SparkServerArgs(printCommand, args.configFile, args.watchUI)
}

class SparkKernelFactory(
  dependencyFetchers: Map[String, DependencyFetcher[IO]])(implicit
  contextShift: ContextShift[IO]
) extends IOKernelFactory(dependencyFetchers) {
  override protected def mkKernel(
    getNotebook: () => IO[Notebook],
    deps: Map[String, List[(String, File)]],
    subKernels: Map[String, LanguageInterpreter.Factory[IO]],
    statusUpdates: Publish[IO, KernelStatusUpdate],
    config: PolynoteConfig,
    extraClassPath: List[File],
    settings: Settings,
    outputDir: AbstractFile,
    parentClassLoader: ClassLoader
  ): IO[PolyKernel] = IO.pure(SparkPolyKernel(getNotebook, deps, subKernels, statusUpdates, extraClassPath, settings, parentClassLoader, config))
}

class SparkRemoteKernelFactory(
  transport: Transport[_])(implicit
  contextShift: ContextShift[IO],
  timer: Timer[IO]
) extends KernelFactory[IO] {
  def launchKernel(getNotebook: () => IO[Notebook], statusUpdates: Publish[IO, KernelStatusUpdate], config: PolynoteConfig): IO[KernelAPI[IO]] =
    RemoteSparkKernel(statusUpdates, getNotebook, config, transport)
}