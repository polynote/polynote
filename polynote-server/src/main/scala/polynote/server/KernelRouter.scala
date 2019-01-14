package polynote.server

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors

import cats.{Applicative, Monad}
import cats.effect.{ContextShift, IO}
import cats.effect.concurrent.{MVar, Ref}
import cats.syntax.functor._
import cats.syntax.flatMap._
import cats.syntax.parallel._
import cats.instances.list._
import cats.effect.internals.IOContextShift
import fs2.concurrent.Topic
import polynote.config.RepositoryConfig
import polynote.kernel.dependency.DependencyFetcher
import polynote.kernel.lang.python.PythonInterpreter
import polynote.kernel._
import polynote.kernel.lang.LanguageKernel
import polynote.messages.{Notebook, NotebookConfig, TinyMap}

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.reflect.io.{AbstractFile, VirtualDirectory}
import scala.tools.nsc.Settings

abstract class KernelRouter[F[_]](implicit F: Monad[F]) {

  // return the cached kernel for the notebook, or create one if there is no cached kernel
  def getOrCreateKernel(notebookRef: Ref[F, Notebook], taskInfo: TaskInfo, statusUpdates: Topic[F, KernelStatusUpdate]): F[Kernel[F]] =
    notebookRef.get.flatMap {
      notebook => getKernel(notebook.path).map(F.pure).getOrElse(createKernel(notebookRef, taskInfo, statusUpdates))
    }

  // get the cached kernel for the notebook, if it exists
  def getKernel(path: String): Option[Kernel[F]]

  // create and cache a new kernel for the notebook, even if one is already cached (discards existing kernel)
  def createKernel(notebookRef: Ref[F, Notebook], taskInfo: TaskInfo, statusUpdates: Topic[F, KernelStatusUpdate]): F[Kernel[F]]

}

class PolyKernelRouter(
  dependencyFetchers: Map[String, DependencyFetcher[IO]],
  subKernels: Map[String, LanguageKernel.Factory[IO]])(implicit
  downloadContext: ContextShift[IO]
) extends KernelRouter[IO] {

  private val kernels = new mutable.HashMap[String, PolyKernel]

  protected def settings: scala.tools.nsc.Settings = PolyKernel.defaultBaseSettings
  protected def outputDir: scala.reflect.io.AbstractFile = PolyKernel.defaultOutputDir
  protected def parentClassLoader: ClassLoader = PolyKernel.defaultParentClassLoader
  protected def extraClassPath: List[File] = Nil

  override def getKernel(path: String): Option[Kernel[IO]] = kernels.get(path)

  protected def mkKernel(
    notebookRef: Ref[IO, Notebook],
    deps: Map[String, List[(String, File)]],
    subKernels: Map[String, LanguageKernel.Factory[IO]],
    statusUpdates: Topic[IO, KernelStatusUpdate],
    extraClassPath: List[File] = Nil,
    settings: Settings,
    outputDir: AbstractFile,
    parentClassLoader: ClassLoader
  ): PolyKernel = PolyKernel(notebookRef, deps, subKernels, statusUpdates, extraClassPath, settings, outputDir, parentClassLoader)

  override def createKernel(notebookRef: Ref[IO, Notebook], taskInfo: TaskInfo, statusUpdates: Topic[IO, KernelStatusUpdate]): IO[Kernel[IO]] = for {
    notebook <- notebookRef.get
    config    = notebook.config.getOrElse(NotebookConfig(None, None))
    _        <- statusUpdates.publish1(KernelBusyState(busy = true, alive = true))
    deps     <- fetchDependencies(config, statusUpdates)
    numDeps   = deps.values.map(_.size).sum
    _        <- statusUpdates.publish1(UpdatedTasks(taskInfo.copy(progress = (numDeps.toDouble / (numDeps + 1) * 255).toByte) :: Nil))
    kernel    = mkKernel(notebookRef, deps, subKernels, statusUpdates, extraClassPath, settings, outputDir, parentClassLoader)
    _         = kernels.put(notebook.path, kernel)
    _        <- kernel.init
    _        <- statusUpdates.publish1(KernelBusyState(busy = false, alive = true))
  } yield kernel

  private def fetchDependencies(config: NotebookConfig, statusUpdates: Topic[IO, KernelStatusUpdate]) = {
    val dependenciesTask = TaskInfo("Dependencies", "Fetch dependencies", "Resolving dependencies", TaskStatus.Running)
    for {
      _       <- statusUpdates.publish1(UpdatedTasks(dependenciesTask :: Nil))
      deps    <- resolveDependencies(config, dependenciesTask, statusUpdates)
      fetched <- downloadDependencies(deps, dependenciesTask, statusUpdates)
      fin      = dependenciesTask.copy(detail = s"Downloaded ${deps.size} dependencies", status = TaskStatus.Complete, progress = 255.toByte)
      _       <- statusUpdates.publish1(UpdatedTasks(fin :: Nil))
    } yield fetched
  }

  private def resolveDependencies(config: NotebookConfig, taskInfo: TaskInfo, statusUpdates: Topic[IO, KernelStatusUpdate]) = {
    val fetch = config.dependencies.toList
      .flatMap(_.toList)
      .flatMap {
        case (lang, langDeps) => dependencyFetchers.get(lang).map {
          fetcher =>
            fetcher.fetchDependencyList(config.repositories.getOrElse(Nil), TinyMap(Map(lang -> langDeps)) :: Nil, taskInfo, statusUpdates).map {
              _.map {
                case (name, ioFile) => (lang, name, ioFile)
              }
            }
        }
      }
    fetch.parSequence.map {
      depDeps =>
        val flat = depDeps.flatten
        flat
    }
  }

  // TODO: ignoring download errors for now, until the weirdness of resolving nonexisting artifacts is solved
  private def downloadFailed(err: Throwable): IO[Option[(String, String, File)]] = IO {
    System.err.println(err.getMessage)
  }.map(_ => None)

  private def downloadDependencies(deps: List[(String, String, IO[File])], taskInfo: TaskInfo, statusUpdates: Topic[IO, KernelStatusUpdate]) = {
    val completedCounter = Ref.unsafe[IO, Int](0)
    val numDeps = deps.size
    deps.map {
      case (lang, name, ioFile) => for {
        download     <- ioFile.start
        file         <- download.join
        _            <- completedCounter.update(_ + 1)
        numCompleted <- completedCounter.get
        statusUpdate  = taskInfo.copy(detail = s"Downloaded $numCompleted / $numDeps", progress = ((numCompleted.toDouble * 255) / numDeps).toByte)
        _            <- statusUpdates.publish1(UpdatedTasks(statusUpdate :: Nil))
      } yield (lang, name, file)
    }.map(_.map(Some(_)).handleErrorWith(downloadFailed)).parSequence.map {
      fetched => fetched.flatten.groupBy(_._1).mapValues(_.map {
        case (_, name, file) => (name, file)
      })
    }
  }

}