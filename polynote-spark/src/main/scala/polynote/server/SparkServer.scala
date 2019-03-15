package polynote.server
import java.io.File

import cats.effect.{ContextShift, IO}
import polynote.config.PolynoteConfig
import polynote.kernel.dependency.DependencyFetcher
import polynote.kernel.{KernelAPI, KernelStatusUpdate, PolyKernel, SparkPolyKernel}
import polynote.kernel.lang.LanguageInterpreter
import polynote.kernel.remote.RemoteSparkKernel
import polynote.kernel.util.Publish
import polynote.messages.Notebook
import polynote.server.SparkServer.interpreters

import scala.reflect.io.AbstractFile
import scala.tools.nsc.Settings

object SparkServer extends Server with SparkKernelLaunching

trait SparkKernelLaunching extends KernelLaunching {
  override protected def kernelFactory(config: PolynoteConfig): KernelFactory[IO] = new SparkKernelFactory(Map("scala" -> dependencyFetcher), interpreters, config)
}

class SparkKernelFactory(
  dependencyFetchers: Map[String, DependencyFetcher[IO]],
  interpreters: Map[String, LanguageInterpreter.Factory[IO]],
  config: PolynoteConfig)(implicit
  contextShift: ContextShift[IO]
) extends IOKernelFactory(dependencyFetchers, interpreters, config) {
  override protected def mkKernel(
    getNotebook: () => IO[Notebook],
    deps: Map[String, List[(String, File)]],
    subKernels: Map[String, LanguageInterpreter.Factory[IO]],
    statusUpdates: Publish[IO, KernelStatusUpdate],
    extraClassPath: List[File],
    settings: Settings,
    outputDir: AbstractFile,
    parentClassLoader: ClassLoader
  ): IO[PolyKernel] = IO.pure(SparkPolyKernel(getNotebook, deps, subKernels, statusUpdates, extraClassPath, settings, parentClassLoader, config))
}

class SparkRemoteKernelFactory(val interpreters: Map[String, LanguageInterpreter.Factory[IO]], config: PolynoteConfig) extends KernelFactory[IO] {
  def launchKernel(getNotebook: () => IO[Notebook], statusUpdates: Publish[IO, KernelStatusUpdate]): IO[KernelAPI[IO]] =
    IO(new RemoteSparkKernel(statusUpdates, getNotebook, config))
}