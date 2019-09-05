package polynote.kernel.dependency

import java.io.File
import java.util.concurrent.{ConcurrentHashMap, ExecutorService}
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}

import cats.{Applicative, Traverse}
import cats.data.{Validated, ValidatedNel}
import cats.effect.concurrent.Ref
import cats.syntax.traverse._
import cats.instances.list._
import coursier.cache.{ArtifactError, Cache, CacheLogger, FileCache}
import coursier.core.Repository.Fetch
import coursier.{Artifacts, Attributes, Dependency, MavenRepository, Module, ModuleName, Organization, Repository, Resolution, Resolve}
import coursier.core.{Artifact, Classifier, Configuration, Exclusions, Repository, Resolution, ResolutionProcess, Type}
import coursier.error.ResolutionError
import coursier.ivy.IvyRepository
import coursier.params.ResolutionParams
import coursier.util.{EitherT, Sync}
import polynote.config.{RepositoryConfig, ivy, maven}
import polynote.kernel.{TaskInfo, TaskManager, UpdatedTasks}
import polynote.kernel.environment.{CurrentNotebook, CurrentTask, Env}
import polynote.messages.NotebookConfig
import zio.{Task, TaskR, ZIO}
import zio.interop.catz._

import scala.concurrent.ExecutionContext

object ZIOCoursierFetcher {
  type ArtifactTask[A] = TaskR[CurrentTask, A]
  type OuterTask[A] = TaskR[TaskManager, A]
  //type ArtifactTask[A] = TaskR[CurrentTask, A]

  // TODO: should factor out the "sub-task" thing into a general-purpose concept. It would help with UI treatment too.
  trait ParentTask {
    def newSubtask(): Task[Unit]
    def completedSubtask(): Task[Unit]
  }

  // Rotate the current task to be the parent task
  def parentTask: TaskR[CurrentTask, ParentTask] = CurrentTask.access.map {
    ref => new ParentTask {
      private val totalTasks = new AtomicInteger(0)
      private val completedTasks = new AtomicInteger(0)
      override def newSubtask(): Task[Unit] = ZIO.effectTotal(totalTasks.incrementAndGet()).unit
      override def completedSubtask(): Task[Unit] = for {
        completed <- ZIO.effectTotal(completedTasks.incrementAndGet())
        total     <- ZIO.effectTotal(totalTasks.get())
        progress   = if (total == 0) 0.0 else completed.toDouble / total
        _         <- ref.update(_.progress(progress))
      } yield ()
    }
  }

  private val excludedOrgs = Set(Organization("org.scala-lang"), Organization("org.apache.spark"))
  val artifactTypes = coursier.core.Resolution.defaultTypes - Type.testJar
  private val cache = FileCache[ArtifactTask]()

  def fetch(language: String): TaskR[CurrentNotebook with TaskManager, List[File]] = TaskManager.run("Coursier", "Dependencies", "Resolving dependencies") {
    for {
      config       <- CurrentNotebook.config
      dependencies  = config.dependencies.flatMap(_.toMap.get(language)).map(_.toList).getOrElse(Nil)
      repoConfigs   = config.repositories.map(_.toList).getOrElse(Nil)
      exclusions    = config.exclusions.map(_.toList).getOrElse(Nil)
      repositories <- ZIO.fromEither(repositories(repoConfigs))
      resolution   <- resolution(dependencies, exclusions, repositories)
      _            <- CurrentTask.update(_.copy(detail = "Downloading dependencies...", progress = 0))
      result       <- download(resolution).provideSomeM(Env.enrichM[TaskManager](parentTask))
    } yield result.map(_._2)
  }

  private def repositories(repositories: List[RepositoryConfig]): Either[Throwable, List[Repository]] = repositories.collect {
    case repo @ ivy(base, _, _, changing) =>
      val baseUri = base.stripSuffix("/") + "/"
      val artifactPattern = s"$baseUri${repo.artifactPattern}"
      val metadataPattern = s"$baseUri${repo.metadataPattern}"
      Validated.fromEither(IvyRepository.parse(artifactPattern, Some(metadataPattern), changing = changing)).toValidatedNel
    case maven(base, changing) =>
      val repo = MavenRepository(base, changing = changing)
      Validated.validNel(repo)
  }.sequence[ValidatedNel[String, ?], Repository].leftMap {
    errs => new RuntimeException(s"Errors parsing repositories:\n- ${errs.toList.mkString("\n- ")}")
  }.toEither

  // TODO: break up this method
  private def resolution(
    dependencies: List[String],
    exclusions: List[String],
    repositories: List[Repository]
  ): TaskR[CurrentTask, Resolution] = {
    val coursierExclude = exclusions.map { exclusionStr =>
      exclusionStr.split(":") match {
        case Array(org, name) => (Organization(org), ModuleName(name))
        case Array(org) => (Organization(org), Exclusions.allNames)
      }
    }.toSet ++ excludedOrgs.map(_ -> Exclusions.allNames)

    val coursierDeps = dependencies.map {
      moduleStr =>
        val (org, name, typ, config, classifier, ver) = moduleStr.split(':') match {
          case Array(org, name, ver) => (Organization(org), ModuleName(name), Type.empty, Configuration.default, Classifier.empty, ver)
          case Array(org, name, classifier, ver) => (Organization(org), ModuleName(name), Type.empty, Configuration.default, Classifier(classifier), ver)
          case Array(org, name, typ, classifier, ver) => (Organization(org), ModuleName(name), Type(typ), Configuration.default, Classifier(classifier), ver)
          case Array(org, name, typ, config, classifier, ver) => (Organization(org), ModuleName(name), Type(typ), Configuration(config), Classifier(classifier), ver)
          case _ => throw new Exception(s"Unable to parse dependency '$moduleStr'")
        }
        Dependency.of(Module(org, name), ver)
          .withConfiguration(config)
          .withAttributes(Attributes(typ, classifier))
          .withExclusions(coursierExclude)
          .withTransitive(classifier.value != "all")
    }

    val rootModules = coursierDeps.map(_.module).toSet

    // need to do some magic on the default repositories, because the sax parser for maven poms don't work
    val mavenRepository = classOf[MavenRepository]
    val usePom = mavenRepository.getDeclaredField("useSaxParser")
    usePom.setAccessible(true)

    val repos = (repositories ++ Resolve(cache).repositories).map {
      case maven: MavenRepository =>
        usePom.set(maven, false)
        maven
      case other => other
    }

    def recover(err: Throwable): ArtifactTask[Resolution] = err match {
      case err: ResolutionError.Several =>
        err.errors.flatMap {
          case err: ResolutionError.CantDownloadModule if rootModules(err.module) => Some(err)
          case err: ResolutionError.CantDownloadModule => None
          case err => Some(err)
        }.headOption.fold(ZIO.succeed(err.resolution).absorb)(err => ZIO.fail(err))
      case err: ResolutionError.CantDownloadModule =>
        if (rootModules(err.module)) ZIO.fail(err) else ZIO.succeed(err.resolution)
      case err => ZIO.fail(err)
    }

    val totalCount = new AtomicInteger(rootModules.size)
    val resolvedCount = new AtomicInteger(0)

    def addMoreModules(n: Int) = ZIO(totalCount.addAndGet(n)).flatMap {
      total => CurrentTask.update(_.progress(resolvedCount.get.toDouble / total))
    }

    def resolveModules(n: Int) = ZIO(resolvedCount.addAndGet(n)).flatMap {
      resolved => CurrentTask.update(_.progress(resolved.toDouble / totalCount.get))
    }

    def countingFetcher(fetcher: ResolutionProcess.Fetch[ArtifactTask]): ResolutionProcess.Fetch[ArtifactTask] = {
      modules: Seq[(Module, String)] =>
        addMoreModules(modules.size) *> fetcher(modules).flatMap {
          md => resolveModules(md.size).const(md)
        }
    }

    Resolve(cache)
      .addDependencies(coursierDeps: _*)
      .withRepositories(repos)
      .withResolutionParams(ResolutionParams())
      .transformFetcher(countingFetcher)
      .io
      .catchAll(recover)
  }

  private def download(
    resolution: Resolution,
    maxIterations: Int = 100
  ): TaskR[TaskManager with ParentTask, List[(String, File)]] = ZIO.runtime[Any].flatMap {
    runtime =>
      ZIO.access[ParentTask](identity).flatMap {
        parentTask =>
          Artifacts(new TaskManagedCache(cache, parentTask, runtime.Platform.executor.asEC)).withResolution(resolution).withMainArtifacts(true).io.map {
            artifacts => artifacts.toList.map {
              case (artifact, file) => artifact.url -> file
            }
          }
      }
  }

  // coursier doesn't have instances for ZIO built in
  implicit def zioSync[R]: Sync[TaskR[R, ?]] = new Sync[TaskR[R, ?]] {
    def delay[A](a: => A): TaskR[R, A] = ZIO.effect(a)
    def handle[A](a: TaskR[R, A])(f: PartialFunction[Throwable, A]): TaskR[R, A] = a.catchSome(f andThen ZIO.succeed)
    def fromAttempt[A](a: Either[Throwable, A]): TaskR[R, A] = ZIO.fromEither(a)
    def gather[A](elems: Seq[TaskR[R, A]]): TaskR[R, Seq[A]] = Traverse[List].sequence[TaskR[R, ?], A](elems.toList)
    def point[A](a: A): TaskR[R, A] = ZIO.succeed(a)
    def bind[A, B](elem: TaskR[R, A])(f: A => TaskR[R, B]): TaskR[R, B] = elem.flatMap(f)

    def schedule[A](pool: ExecutorService)(f: => A): TaskR[R, A] = ZIO.effect(f).on {
      pool match {
        case pool: ExecutionContext => pool
        case pool => ExecutionContext.fromExecutorService(pool)
      }
    }
  }

  /**
    * Wraps an underlying [[FileCache]] such that each cache task is managed by the TaskManager
    */
  class TaskManagedCache(underlying: FileCache[ArtifactTask], parentTask: ParentTask, val ec: ExecutionContext) extends Cache[OuterTask] {
    def fetch: Artifact => EitherT[OuterTask, String, String] = {
      artifact =>
        val name = taskName(artifact.url)
        EitherT(TaskManager.run(name, name, artifact.url)(logged(_.fetch(artifact).run)))
    }

    def file(artifact: Artifact): EitherT[OuterTask, ArtifactError, File] = {
      val name = taskName(artifact.url)
      EitherT(TaskManager.run(name, name, artifact.url)(logged(_.file(artifact).run)))
    }

    def logged[A](fn: FileCache[ArtifactTask] => ArtifactTask[A]): TaskR[CurrentTask, A] = for {
      logger <- TaskManagedCache.logger
      result <- fn(underlying.withLogger(logger))
    } yield result

  }

  private def taskName(url: String) = url.lastIndexOf('/') match {
    case -1  => url
    case idx => url.substring(idx + 1)
  }

  object TaskManagedCache {
    def logger: ArtifactTask[CacheLogger] = for {
      taskRef  <- CurrentTask.access
      taskInfo <- taskRef.get
      runtime  <- ZIO.runtime[Any]
    } yield {
      val taskId = taskInfo.id
      new CacheLogger {
        private val knownLength = new AtomicLong(0)

        override def downloadLength(url: String, totalLength: Long, alreadyDownloaded: Long, watching: Boolean): Unit =
          if (taskName(url) == taskId) {
            knownLength.set(totalLength)
          }

        override def downloadProgress(url: String, downloaded: Long): Unit =
          if (taskName(url) == taskId) {
            val progress = knownLength.get() match {
              case 0 => 0.0
              case n => downloaded.toDouble / n
            }
            runtime.unsafeRun(taskRef.update(_.progress(progress)))
          }

        override def downloadedArtifact(url: String, success: Boolean): Unit =
          if (taskName(url) == taskId) {
            runtime.unsafeRun(taskRef.update(_.completed))
          }
      }
    }

  }
}


