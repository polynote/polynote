package polynote.kernel.dependency

import java.io.{File, FileOutputStream}
import java.net.URI
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.{ConcurrentHashMap, ExecutorService}
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}

import cats.{Applicative, Traverse}
import cats.data.{Validated, ValidatedNel}
import cats.effect.concurrent.Ref
import cats.effect.LiftIO
import cats.syntax.alternative._
import cats.syntax.apply._
import cats.syntax.either._
import cats.syntax.traverse._
import cats.instances.either._
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
import polynote.kernel.util.{DownloadableFile, DownloadableFileProvider}
import polynote.messages.NotebookConfig
import zio.blocking.{Blocking, effectBlocking, blocking}
import zio.{Task, RIO, ZIO, ZManaged}
import zio.interop.catz._

import scala.concurrent.ExecutionContext
import scala.tools.nsc.interpreter.InputStream

object CoursierFetcher {
  type ArtifactTask[A] = RIO[CurrentTask, A]
  type OuterTask[A] = RIO[TaskManager with CurrentTask, A]
  //type ArtifactTask[A] = RIO[CurrentTask, A]

  private val excludedOrgs = Set(Organization("org.scala-lang"), Organization("org.apache.spark"))
  private val cache = FileCache[ArtifactTask]()

  def fetch(language: String): RIO[CurrentNotebook with TaskManager with Blocking, List[(Boolean, String, File)]] = TaskManager.run("Coursier", "Dependencies", "Resolving dependencies") {
    for {
      config       <- CurrentNotebook.config
      dependencies  = config.dependencies.flatMap(_.toMap.get(language)).map(_.toList).getOrElse(Nil)
      (deps, uris)  = splitDependencies(dependencies)
      repoConfigs   = config.repositories.map(_.toList).getOrElse(Nil)
      exclusions    = config.exclusions.map(_.toList).getOrElse(Nil)
      repositories <- ZIO.fromEither(repositories(repoConfigs))
      resolution   <- resolution(deps, exclusions, repositories)
      _            <- CurrentTask.update(_.copy(detail = "Downloading dependencies...", progress = 0))
      downloadDeps <- download(resolution).fork
      downloadUris <- downloadUris(uris).fork
      downloaded   <- downloadDeps.join.map2(downloadUris.join)(_ ++ _)
    } yield downloaded
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
  ): RIO[CurrentTask, Resolution] = ZIO {
    val coursierExclude = exclusions.map { exclusionStr =>
      exclusionStr.split(":") match {
        case Array(org, name) => (Organization(org), ModuleName(name))
        case Array(org) => (Organization(org), Exclusions.allNames)
      }
    }.toSet ++ excludedOrgs.map(_ -> Exclusions.allNames)

    lazy val coursierDeps = dependencies.map {
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

    lazy val rootModules = coursierDeps.map(_.module).toSet

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
          md => resolveModules(md.size).as(md)
        }
    }



    Resolve(cache)
      .addDependencies(coursierDeps: _*)
      .withRepositories(repos)
      .withResolutionParams(ResolutionParams())
      .transformFetcher(countingFetcher)
      .io
      .catchAll(recover)
  }.flatten  // this is pretty lazy, it's just so we can throw an exception in the main block.

  private def download(
    resolution: Resolution,
    maxIterations: Int = 100
  ): RIO[TaskManager with CurrentTask, List[(Boolean, String, File)]] = ZIO.runtime[Any].flatMap {
    runtime =>
      Artifacts(new TaskManagedCache(cache, runtime.Platform.executor.asEC)).withResolution(resolution).withMainArtifacts(true).ioResult.map {
        artifactResult =>
          artifactResult.detailedArtifacts.toList.map {
            case (dep, pub, artifact, file) =>
              (resolution.rootDependencies.contains(dep), artifact.url, file)
          }
      }
  }

  private def downloadUris(uris: List[URI]): RIO[TaskManager with CurrentTask with Blocking, List[(Boolean, String, File)]] = {
    ZIO.collectAllPar {
      uris.map {
        uri => for {
          download <- TaskManager.runSubtask(uri.toString, uri.toString){
            fetchUrl(uri, cacheLocation(uri).toFile)
          }
        } yield (true, uri.toString, download)
      }
    }
  }

  protected def fetchUrl(uri: URI, localFile: File, chunkSize: Int = 8192): RIO[Blocking with CurrentTask, File] = {
    def downloadToFile(file: DownloadableFile, cacheFile: File) = for {
      blockingEnv <- ZIO.access[Blocking](identity)
      task        <- CurrentTask.access
      ec          <- blockingEnv.blocking.blockingExecutor.map(_.asEC)
      size        <- blocking(LiftIO[Task].liftIO(file.size))
      _           <- ZIO(Files.createDirectories(cacheFile.toPath.getParent))
      _           <- ZManaged.fromAutoCloseable(effectBlocking(new FileOutputStream(cacheFile))).use {
        os =>
          val fs2IS = fs2.io.readInputStream[Task](effectBlocking(file.openStream.unsafeRunSync()).provide(blockingEnv), chunkSize, ec)
          val fs2OS = fs2.io.writeOutputStream[Task](ZIO.succeed(os), ec)
          fs2IS.chunks
            .mapAccumulate(0)((n, c) => (n + c.size, c))
            .evalMap {
              case (i, chunk) => task.update(_.progress(i.toDouble / size)).as(chunk)
            }
            .flatMap(fs2.Stream.chunk)
            .through(fs2OS)
            .compile.drain.onError {
              cause => effectBlocking(cacheFile.delete()).ignore
            }
      }
    } yield ()

    for {
      file        <- ZIO.fromOption(DownloadableFileProvider.getFile(uri)).mapError(_ => new Exception(s"Unable to find provider for uri $uri"))
      inputAsFile  = Paths.get(uri.getPath).toFile
      exists      <- effectBlocking(inputAsFile.exists())
      download    <- if (exists) ZIO.succeed(inputAsFile) else downloadToFile(file, localFile).as(localFile)
    } yield download

  }

  private def splitDependencies(deps: List[String]): (List[String], List[URI]) = {
    val (dependencies, uriList) = deps.map { dep =>

      val asURI = new URI(dep)

      Either.cond(
        // Do we support this protocol (if any?)
        DownloadableFileProvider.isSupported(asURI),
        asURI,
        dep // an unsupported protocol might be a dependency
      )
    }.separate

    (dependencies, uriList)
  }

  protected def cacheLocation(uri: URI): Path = {
    val pathParts = Seq(uri.getScheme, uri.getAuthority, uri.getPath).flatMap(Option(_)) // URI methods sometimes return `null`, great.
    coursier.cache.CacheDefaults.location.toPath.resolve(Paths.get(pathParts.head, pathParts.tail: _*))
  }

  // coursier doesn't have instances for ZIO built in
  implicit def zioSync[R]: Sync[RIO[R, ?]] = new Sync[RIO[R, ?]] {
    def delay[A](a: => A): RIO[R, A] = ZIO.effect(a)
    def handle[A](a: RIO[R, A])(f: PartialFunction[Throwable, A]): RIO[R, A] = a.catchSome(f andThen ZIO.succeed)
    def fromAttempt[A](a: Either[Throwable, A]): RIO[R, A] = ZIO.fromEither(a)
    def gather[A](elems: Seq[RIO[R, A]]): RIO[R, Seq[A]] = Traverse[List].sequence[RIO[R, ?], A](elems.toList)
    def point[A](a: A): RIO[R, A] = ZIO.succeed(a)
    def bind[A, B](elem: RIO[R, A])(f: A => RIO[R, B]): RIO[R, B] = elem.flatMap(f)

    def schedule[A](pool: ExecutorService)(f: => A): RIO[R, A] = ZIO.effect(f).on {
      pool match {
        case pool: ExecutionContext => pool
        case pool => ExecutionContext.fromExecutorService(pool)
      }
    }
  }

  /**
    * Wraps an underlying [[FileCache]] such that each cache task is managed by the TaskManager
    */
  class TaskManagedCache(underlying: FileCache[ArtifactTask], val ec: ExecutionContext) extends Cache[OuterTask] {
    override def fetch: Artifact => EitherT[OuterTask, String, String] = {
      artifact =>
        val name = taskName(artifact.url)
        EitherT(TaskManager.runSubtask(name, name, artifact.url)(logged(_.fetch(artifact).run)))
    }

    override def file(artifact: Artifact): EitherT[OuterTask, ArtifactError, File] = {
      val name = taskName(artifact.url)
      EitherT(TaskManager.runSubtask(name, name, artifact.url)(logged(_.file(artifact).run)))
    }

    def logged[A](fn: FileCache[ArtifactTask] => ArtifactTask[A]): RIO[CurrentTask, A] = for {
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


