package polynote.server

import java.io.File

import cats.effect.concurrent.Ref
import cats.effect.{ContextShift, IO}
import fs2.concurrent.Topic
import polynote.kernel.{KernelStatusUpdate, PolyKernel, SparkPolyKernel}
import polynote.kernel.dependency.DependencyFetcher
import polynote.kernel.lang.LanguageKernel
import polynote.messages.Notebook

import scala.reflect.io.AbstractFile
import scala.tools.nsc.Settings

class SparkPolyKernelRouter(
  dependencyFetchers: Map[String, DependencyFetcher[IO]],
  subKernels: Map[String, LanguageKernel.Factory[IO]])(implicit
  downloadContext: ContextShift[IO]
) extends PolyKernelRouter(dependencyFetchers, subKernels) {

  override protected def mkKernel(
    notebookRef: Ref[IO, Notebook],
    deps: Map[String, List[(String, File)]],
    subKernels: Map[String, LanguageKernel.Factory[IO]],
    statusUpdates: Topic[IO, KernelStatusUpdate],
    extraClassPath: List[File],
    settings: Settings,
    outputDir: AbstractFile,
    parentClassLoader: ClassLoader
  ): IO[SparkPolyKernel] = IO.pure(SparkPolyKernel(notebookRef, deps, subKernels, statusUpdates, extraClassPath, settings, parentClassLoader))

}
