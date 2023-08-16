package polynote.kernel.dependency

import java.io.{File, FileOutputStream, IOException}
import java.net.URI
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import cats.data.Validated
import cats.instances.either._
import cats.instances.list._
import cats.syntax.alternative._
import cats.syntax.traverse._
import coursier.cache.{ArtifactError, Cache, CacheLogger, FileCache}
import coursier.core._
import coursier.credentials.{DirectCredentials, Credentials => CoursierCredentials}
import coursier.error.ResolutionError
import coursier.error.conflict.UnsatisfiedRule
import coursier.graph.ReverseModuleTree
import coursier.ivy.IvyRepository
import coursier.params.ResolutionParams
import coursier.params.rule.Rule
import coursier.util.{Artifact => CoursierArtifact, EitherT, ModuleMatchers, Sync}
import coursier.{Artifacts, Attributes, Dependency, MavenRepository, Module, ModuleName, Organization, Resolve}
import polynote.config.{RepositoryConfig, ivy, maven, Credentials => CredentialsConfig}
import polynote.kernel.environment.{Config, CurrentNotebook, CurrentTask}
import polynote.kernel.logging.Logging
import polynote.kernel.task.TaskManager
import polynote.kernel.util.DepsParser.flattenDeps
import polynote.kernel.util.{DownloadableFile, DownloadableFileProvider, LocalFile}
import zio.blocking.{Blocking, effectBlocking}
import zio.stream.ZStream
import zio.{RIO, Task, UIO, URIO, ZIO, ZManaged}
import scala.concurrent.ExecutionContext

object CoursierFetcher {
  type ArtifactTask[A] = RIO[CurrentTask, A]
  type OuterTask[A] = RIO[TaskManager with CurrentTask, A]

  private val excludedOrgs = Set(Organization("org.scala-lang"), Organization("org.apache.spark"))
  private val baseCache = FileCache[ArtifactTask]()

  def fetch(language: String): RIO[Logging with Config with CurrentNotebook with TaskManager with Blocking, List[Artifact]] = TaskManager.run("Coursier", "Dependencies", "Resolving dependencies") {
    for {
      polynoteConfig <- Config.access
      config         <- CurrentNotebook.config
      configDeps      = config.dependencies.flatMap(_.toMap.get(language)).map(_.distinct.toList).getOrElse(Nil)
      flattenedDeps  <- flattenDeps(configDeps)
      splitRes       <- splitDependencies(flattenedDeps)
      (deps, uris)    = splitRes
      repoConfigs     = config.repositories.map(_.toList).getOrElse(Nil)
      exclusions      = config.exclusions.map(_.toList).getOrElse(Nil)
      credentials    <- loadCredentials(polynoteConfig.credentials)
      repositories   <- ZIO.fromEither(repositories(repoConfigs, credentials))
      cache           = baseCache.addCredentials(credentials: _*)
      resolution     <- resolution(deps, exclusions, repositories, cache)
      _              <- CurrentTask.update(_.copy(detail = "Downloading dependencies...", progress = 0))
      downloadDeps   <- download(resolution, cache).fork
      downloadUris   <- downloadUris(uris).fork
      downloaded     <- ZIO.mapN(downloadDeps.join, downloadUris.join)(_ ++ _)
    } yield downloaded
  }

  private def loadCredentials(credentials: CredentialsConfig): URIO[Logging, List[DirectCredentials]] = credentials.coursier match {
    case Some(CredentialsConfig.Coursier(path)) =>
      Task(CoursierCredentials(new File(path), optional = false).get().toList)
        .catchAll(err => Logging.error("Failed to load credentials", err).as(Nil))
    case None => UIO(Nil)
  }

  private def repositories(repositories: List[RepositoryConfig], credentials: List[DirectCredentials]): Either[Throwable, List[Repository]] = repositories.collect {
    case repo @ ivy(base, _, _, changing) =>
      val baseUri = base.stripSuffix("/") + "/"
      val artifactPattern = s"$baseUri${repo.artifactPattern}"
      val metadataPattern = s"$baseUri${repo.metadataPattern}"
      Validated.fromEither(IvyRepository.parse(
        artifactPattern,
        Some(metadataPattern),
        changing = changing
      )).toValidatedNel
    case maven(base, changing) =>
      val repo = MavenRepository(base).withChanging(changing)
      Validated.validNel(repo)
  }.sequence.leftMap {
    errs => new RuntimeException(s"Errors parsing repositories:\n- ${errs.toList.mkString("\n- ")}")
  }.toEither

  // TODO: break up this method
  private def resolution(
    dependencies: List[String],
    exclusions: List[String],
    repositories: List[Repository],
    cache: FileCache[ArtifactTask]
  ): RIO[CurrentTask, Resolution] = ZIO {
    val coursierExclude = exclusions.map { exclusionStr =>
      exclusionStr.split(":") match {
        case Array(org, name) => (Organization(org), ModuleName(name))
        case Array(org) => (Organization(org), Exclusions.allNames)
      }
    }.toSet ++ excludedOrgs.map(_ -> Exclusions.allNames)

    lazy val coursierDeps = dependencies.map {
      moduleStr =>
        val (org, name, typ, config, classifier, ver) = moduleStr.stripSuffix(noCacheSentinel).split(':') match {
          case Array(org, name, ver) => (Organization(org), ModuleName(name), Type.empty, Configuration.empty, Classifier.empty, ver)
          case Array(org, name, typ, ver) => (Organization(org), ModuleName(name), Type(typ), Configuration.empty, Classifier.empty, ver)
          case Array(org, name, typ, classifier, ver) => (Organization(org), ModuleName(name), Type(typ), Configuration.empty, Classifier(classifier), ver)
          case Array(org, name, typ, config, classifier, ver) => (Organization(org), ModuleName(name), Type(typ), Configuration(config), Classifier(classifier), ver)
          case _ => throw new Exception(s"Unable to parse dependency '$moduleStr'")
        }
        Dependency(Module(org, name), ver)
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
      .withResolutionParams(ResolutionParams().addReconciliation(ModuleMatchers.all -> Reconciliation.Relaxed))
      .transformFetcher(countingFetcher)
      .io
      .catchAll(recover)
  }.flatten  // this is pretty lazy, it's just so we can throw an exception in the main block.

  private def whichClassifiers: RIO[Config, Set[coursier.Classifier]] = for {
    config   <- Config.access
  } yield if (config.downloadSources) Set(Classifier.sources) else Set.empty

  private def artifacts(resolution: Resolution, cache: FileCache[ArtifactTask]) = for {
    runtime          <- ZIO.runtime[Blocking]
    blockingExecutor  = runtime.environment.get.blockingExecutor.asEC
    classifiers      <- whichClassifiers
  } yield Artifacts(new TaskManagedCache(cache, blockingExecutor))
    .withResolution(resolution)
    .withClassifiers(classifiers)
    .withMainArtifacts(true)

  // should this artifact go on the classpath? I.e. does it have classes in it, or is it a source/doc JAR?
  private def isClasspath(pub: Publication) = pub.classifier.isEmpty || pub.classifier.value == "all"

  private def download(
    resolution: Resolution,
    cache: FileCache[ArtifactTask],
    maxIterations: Int = 100
  ): RIO[Blocking with Config with TaskManager with CurrentTask with Logging, List[Artifact]] =
    for {
      artifacts <- artifacts(resolution, cache)
      result    <- artifacts.ioResult
      logging   <- Logging.access
    } yield result.detailedArtifacts.groupBy(_._1.module).toList.flatMap {
      case (module, downloads) => downloads.find(tup => isClasspath(tup._2)) match {
        case Some((dep, pub, artifact, file)) =>
          val sources = downloads.find(_._2.classifier == Classifier.sources).map(_._4)
          Some(Artifact(resolution.rootDependencies.contains(dep), artifact.url, file, sources))
        case None =>
          logging.warnSync(s"Dependency for $module didn't contain any binaries; skipping")
          None
      }
    }

  private def downloadUris(uris: List[URI]): RIO[TaskManager with CurrentTask with Blocking with Logging, List[Artifact]] = {
    ZIO.foreachParN(16)(uris) {
      uri =>
        for {
          download <- TaskManager.runSubtask(uri.toString, uri.toString) {
            fetchUrl(uri, cacheLocation(uri).toFile)
          }
        } yield Artifact(true, uri.toString, download, None)
    }
  }

  protected def fetchUrl(uri: URI, localFile: File, chunkSize: Int = 8192): RIO[Blocking with CurrentTask, File] = {
    def downloadToFile(file: DownloadableFile, cacheFile: File) = for {
      size        <- file.size
      _           <- effectBlocking(Files.createDirectories(cacheFile.toPath.getParent))
      count        = new AtomicInteger(0)
      _           <- ZManaged.fromAutoCloseable(effectBlocking(new FileOutputStream(cacheFile))).use {
        os =>
          ZStream.fromInputStreamManaged(file.openStream.refineToOrDie[IOException]).foreachChunk {
            chunk => for {
              _         <- effectBlocking(os.write(chunk.toArray))
              nextCount <- ZIO.effectTotal(count.addAndGet(chunk.size))
              _         <- CurrentTask.update(_.progress(nextCount.toDouble / size))
            } yield ()
          }
      }
    } yield ()

    val bustCache = uri.toString.endsWith(noCacheSentinel)

    for {
      file        <- DownloadableFileProvider.getFile(uri)
      inputAsFile  = file match {
        case LocalFile(u) => Paths.get(u.getPath).toFile
        case _ => localFile
      }
      exists      <- effectBlocking(inputAsFile.exists())
      download    <- if (exists && !bustCache) ZIO.succeed(inputAsFile) else downloadToFile(file, localFile).as(localFile)
    } yield download

  }

  private def splitDependencies(deps: List[String]): RIO[Blocking, (List[String], List[URI])] = ZIO.foreach(deps) { dep =>
    (for {
      asURI <- ZIO(new URI(dep))
      supported <- DownloadableFileProvider.isSupported(asURI)
    } yield Either.cond(
      test = supported,
      right = asURI,
      left = dep // an unsupported protocol might be a dependency coordinate (like the `foo` in `foo:bar_2.11:1.2.3`)
    )).orElseSucceed(Left(dep)) // the URI constructor can throw
  }.map(_.separate)

  protected def cacheLocation(uri: URI): Path = {
    val pathParts = Seq(uri.getScheme, uri.getAuthority, uri.getPath).flatMap(Option(_)) // URI methods sometimes return `null`, great.
    coursier.cache.CacheDefaults.location.toPath.resolve(Paths.get(pathParts.head, pathParts.tail: _*))
  }

  // coursier doesn't have instances for ZIO built in
  implicit def zioSync[R]: Sync[RIO[R, ?]] = new Sync[RIO[R, ?]] {
    def delay[A](a: => A): RIO[R, A] = ZIO.effect(a)
    def handle[A](a: RIO[R, A])(f: PartialFunction[Throwable, A]): RIO[R, A] = a.catchSome(f andThen (a => ZIO.succeed(a)))
    def fromAttempt[A](a: Either[Throwable, A]): RIO[R, A] = ZIO.fromEither(a)
    def gather[A](elems: Seq[RIO[R, A]]): RIO[R, Seq[A]] = ZIO.collectAllParN(10)(elems)
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
    override def fetch: CoursierArtifact => EitherT[OuterTask, String, String] = {
      artifact =>
        val name = taskName(artifact.url)
        EitherT(TaskManager.runSubtask(name, name, artifact.url)(logged(_.fetch(artifact).run)))
    }

    override def file(artifact: CoursierArtifact): EitherT[OuterTask, ArtifactError, File] = {
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
            runtime.unsafeRun(taskRef.update(t => ZIO.succeed(t.progress(progress))))
          }

        override def downloadedArtifact(url: String, success: Boolean): Unit =
          if (taskName(url) == taskId) {
            runtime.unsafeRun(taskRef.update(t => ZIO.succeed(t.completed)))
          }
      }
    }

  }
}


