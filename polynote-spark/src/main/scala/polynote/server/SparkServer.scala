package polynote.server
import java.io.File

import cats.effect.IO
import polynote.kernel.{KernelStatusUpdate, PolyKernel, SparkPolyKernel}
import polynote.kernel.lang.LanguageInterpreter
import polynote.kernel.util.Publish
import polynote.messages.Notebook

import scala.reflect.io.AbstractFile
import scala.tools.nsc.Settings

object SparkServer extends Server {
  override protected lazy val kernelFactory: KernelFactory[IO] = new IOKernelFactory(Map("scala" -> dependencyFetcher), interpreters) {
    override protected def mkKernel(
      getNotebook: () => IO[Notebook],
      deps: Map[String, List[(String, File)]],
      subKernels: Map[String, LanguageInterpreter.Factory[IO]],
      statusUpdates: Publish[IO, KernelStatusUpdate],
      extraClassPath: List[File],
      settings: Settings,
      outputDir: AbstractFile,
      parentClassLoader: ClassLoader
    ): IO[PolyKernel] = IO.pure(SparkPolyKernel(getNotebook, deps, subKernels, statusUpdates, extraClassPath, settings, parentClassLoader))
  }
}

