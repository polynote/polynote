package polynote.server

import java.io.File

import cats.effect.{ContextShift, Fiber, IO}
import cats.effect.concurrent.Ref
import cats.instances.list._
import cats.syntax.parallel._
import fs2.Stream
import fs2.concurrent.Topic
import polynote.kernel.dependency.DependencyFetcher
import polynote.kernel.lang.LanguageKernel
import polynote.kernel._
import polynote.kernel.context.GlobalInfo
import polynote.kernel.util.{Publish, ReadySignal}
import polynote.messages.{Notebook, NotebookConfig, TinyMap}

import scala.reflect.io.AbstractFile
import scala.tools.nsc.Settings

trait KernelFactory[F[_]] {

  def launchKernel(getNotebook: () => F[Notebook], statusUpdates: Publish[F, KernelStatusUpdate]): F[Kernel[F]]

  def interpreters: Map[String, LanguageKernel.Factory[F, GlobalInfo]]
}

class IOKernelFactory(
  dependencyFetchers: Map[String, DependencyFetcher[IO]],
  val interpreters: Map[String, LanguageKernel.Factory[IO, GlobalInfo]])(implicit
  contextShift: ContextShift[IO]
) extends KernelFactory[IO] {

  protected def settings: scala.tools.nsc.Settings = PolyKernel.defaultBaseSettings
  protected def outputDir: scala.reflect.io.AbstractFile = PolyKernel.defaultOutputDir
  protected def parentClassLoader: ClassLoader = PolyKernel.defaultParentClassLoader
  protected def extraClassPath: List[File] = Nil

  protected def mkKernel(
    getNotebook: () => IO[Notebook],
    deps: Map[String, List[(String, File)]],
    subKernels: Map[String, LanguageKernel.Factory[IO, GlobalInfo]],
    statusUpdates: Publish[IO, KernelStatusUpdate],
    extraClassPath: List[File] = Nil,
    settings: Settings,
    outputDir: AbstractFile,
    parentClassLoader: ClassLoader
  ): IO[PolyKernel] = IO.pure(PolyKernel(getNotebook, deps, subKernels, statusUpdates, extraClassPath, settings, outputDir, parentClassLoader))

  override def launchKernel(getNotebook: () => IO[Notebook], statusUpdates: Publish[IO, KernelStatusUpdate]): IO[Kernel[IO]] = for {
    notebook <- getNotebook()
    path      = notebook.path
    config    = notebook.config.getOrElse(NotebookConfig(None, None))
    taskInfo  = TaskInfo("kernel", "Start", "Kernel starting", TaskStatus.Running)
    deps     <- fetchDependencies(config, statusUpdates)
    numDeps   = deps.values.map(_.size).sum
    _        <- statusUpdates.publish1(UpdatedTasks(taskInfo.copy(progress = (numDeps.toDouble / (numDeps + 1) * 255).toByte) :: Nil))
    kernel   <- mkKernel(getNotebook, deps, interpreters, statusUpdates, extraClassPath, settings, outputDir, parentClassLoader)
    _        <- kernel.init
    _        <- statusUpdates.publish1(UpdatedTasks(taskInfo.copy(progress = 255.toByte, status = TaskStatus.Complete) :: Nil))
    _        <- statusUpdates.publish1(KernelBusyState(busy = false, alive = true))
  } yield kernel

  private def fetchDependencies(config: NotebookConfig, statusUpdates: Publish[IO, KernelStatusUpdate]) = {
    val dependenciesTask = TaskInfo("Dependencies", "Fetch dependencies", "Resolving dependencies", TaskStatus.Running)
    for {
      _       <- statusUpdates.publish1(UpdatedTasks(dependenciesTask :: Nil))
      deps    <- resolveDependencies(config, dependenciesTask, statusUpdates)
      fetched <- downloadDependencies(deps, dependenciesTask, statusUpdates)
      fin      = dependenciesTask.copy(detail = s"Downloaded ${deps.size} dependencies", status = TaskStatus.Complete, progress = 255.toByte)
      _       <- statusUpdates.publish1(UpdatedTasks(fin :: Nil))
    } yield fetched
  }

  private def resolveDependencies(config: NotebookConfig, taskInfo: TaskInfo, statusUpdates: Publish[IO, KernelStatusUpdate]) = {
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

  private def downloadDependencies(deps: List[(String, String, IO[File])], taskInfo: TaskInfo, statusUpdates: Publish[IO, KernelStatusUpdate]) = {
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
