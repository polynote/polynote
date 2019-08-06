package polynote.server

import java.io.File
import java.nio.file.Paths

import cats.effect.{ContextShift, IO, Timer}
import cats.instances.list._
import cats.syntax.parallel._
import polynote.config.PolynoteConfig
import polynote.kernel._
import polynote.kernel.dependency.{DependencyManagerFactory, DependencyProvider}
import polynote.kernel.lang.LanguageInterpreter
import polynote.kernel.util.Publish
import polynote.messages.{Notebook, NotebookConfig, TinyList, TinyMap, TinyString}

import scala.reflect.io.AbstractFile
import scala.tools.nsc.Settings

trait KernelFactory[F[_]] {

  def launchKernel(
    getNotebook: () => F[Notebook],
    statusUpdates: Publish[F, KernelStatusUpdate],
    config: PolynoteConfig
  ): F[KernelAPI[F]]

}

class IOKernelFactory(implicit
  contextShift: ContextShift[IO],
  timer: Timer[IO]
) extends KernelFactory[IO] {

  protected def settings: scala.tools.nsc.Settings = PolyKernel.defaultBaseSettings
  protected def outputDir: scala.reflect.io.AbstractFile = PolyKernel.defaultOutputDir
  protected def parentClassLoader: ClassLoader = PolyKernel.defaultParentClassLoader
  protected def extraClassPath: List[File] = Nil

  protected def mkKernel(
    getNotebook: () => IO[Notebook],
    deps: Map[String, DependencyProvider],
    subKernels: Map[String, LanguageInterpreter.Factory[IO]],
    statusUpdates: Publish[IO, KernelStatusUpdate],
    config: PolynoteConfig,
    extraClassPath: List[File] = Nil,
    settings: Settings,
    outputDir: AbstractFile,
    parentClassLoader: ClassLoader
  ): IO[PolyKernel] = PolyKernel(getNotebook, deps, subKernels, statusUpdates, extraClassPath, settings, outputDir, parentClassLoader, config)

  override def launchKernel(getNotebook: () => IO[Notebook], statusUpdates: Publish[IO, KernelStatusUpdate], polynoteConfig: PolynoteConfig): IO[KernelAPI[IO]] = for {
    notebook <- getNotebook()
    config    = notebook.config.getOrElse(NotebookConfig.empty)
    path      = Paths.get(polynoteConfig.storage.cache, notebook.path).toString
    taskInfo  = TaskInfo("kernel", "Start", "Kernel starting", TaskStatus.Running)
    deps     <- fetchDependencyProviders(config, path, statusUpdates)
    numDeps   = deps.values.map(_.dependencies.size).sum
    _        <- statusUpdates.publish1(UpdatedTasks(taskInfo.copy(progress = (numDeps.toDouble / (numDeps + 1) * 255).toByte) :: Nil))
    kernel   <- mkKernel(getNotebook, deps, LanguageInterpreter.factories, statusUpdates, polynoteConfig, extraClassPath, settings, outputDir, parentClassLoader)
    _        <- kernel.init()
    _        <- statusUpdates.publish1(UpdatedTasks(taskInfo.copy(progress = 255.toByte, status = TaskStatus.Complete) :: Nil))
    _        <- statusUpdates.publish1(KernelBusyState(busy = false, alive = true))
  } yield kernel

  private def fetchDependencyProviders(config: NotebookConfig, path: String, statusUpdates: Publish[IO, KernelStatusUpdate]): IO[Map[String, DependencyProvider]] = {
    val dependenciesTask = TaskInfo("Dependencies", "Fetch dependencies", "Resolving dependencies", TaskStatus.Running)
    for {
      _       <- statusUpdates.publish1(UpdatedTasks(dependenciesTask :: Nil))
      deps    <- getDependencies(config, path, dependenciesTask, statusUpdates)
      fin      = dependenciesTask.copy(detail = s"Downloaded ${deps.size} dependencies", status = TaskStatus.Complete, progress = 255.toByte)
      _       <- statusUpdates.publish1(UpdatedTasks(fin :: Nil))
    } yield deps
  }

  private def getDependencies(config: NotebookConfig, path: String, taskInfo: TaskInfo, statusUpdates: Publish[IO, KernelStatusUpdate]): IO[Map[String, DependencyProvider]] = {
    LanguageInterpreter.factories.mapValues(_.depManagerFactory).toList.map {
      case (lang, makeDepManager) =>
        val depManager = makeDepManager(path, taskInfo, statusUpdates)
        val deps = config.dependencies.flatMap(_.get(lang)).getOrElse(TinyList(Nil))
        depManager.getDependencyProvider(config.repositories.getOrElse(Nil), deps, config.exclusions.getOrElse(Nil)).map(lang.toString -> _)
    }.parSequence.map(_.toMap)
  }
}
