package polynote.kernel.dependency

import java.io._
import java.net.URI
import java.nio.file.{Path, Paths}
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ConcurrentHashMap, Executors}

import cats.data.{Validated, ValidatedNel}
import cats.effect.{ContextShift, IO}
import cats.implicits._
import coursier.cache.{CacheLogger, FileCache}
import coursier.core._
import coursier.error.ResolutionError
import coursier.interop.cats._
import coursier.ivy.IvyRepository
import coursier.params.ResolutionParams
import coursier.{Artifacts, Attributes, Dependency, MavenRepository, Module, ModuleName, Organization, Repository, Resolution, Resolve}
import polynote.config.{RepositoryConfig, ivy, maven}
import polynote.kernel._
import polynote.kernel.util.{Publish, newDaemonThreadPool}
import polynote.messages.TinyString

import scala.concurrent.ExecutionContext


// Fetches only Scala dependencies
class CoursierFetcher(val path: String, val taskInfo: TaskInfo, val statusUpdates: Publish[IO, KernelStatusUpdate]) extends ScalaDependencyFetcher {

  protected implicit val executionContext: ExecutionContext = ExecutionContext.fromExecutorService(newDaemonThreadPool("coursier"))
  protected implicit val contextShift: ContextShift[IO] =  IO.contextShift(executionContext)

  private val excludedOrgs = Set(Organization("org.scala-lang"), Organization("org.apache.spark"))
  private val cache = FileCache[IO]()


  private def resolution(
    dependencies: List[String],
    exclusions: List[String],
    repositories: List[Repository],
    statusUpdates: Publish[IO, KernelStatusUpdate],
    taskInfo: TaskInfo
  ): IO[Resolution] = {
    // exclusions are applied to all direct and transitive dependencies.
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
        Dependency(Module(org, name), ver, config, Attributes(typ, classifier), coursierExclude, transitive = classifier.value != "all")

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

    def recover(err: Throwable): IO[Resolution] = err match {
      case err: ResolutionError.Several =>
        err.errors.flatMap {
          case err: ResolutionError.CantDownloadModule if rootModules(err.module) => Some(err)
          case err: ResolutionError.CantDownloadModule => None
          case err => Some(err)
        }.headOption.fold(IO.pure(err.resolution))(IO.raiseError)
      case err: ResolutionError.CantDownloadModule =>
        if (rootModules(err.module)) IO.raiseError(err) else IO.pure(err.resolution)
      case err => IO.raiseError(err)
    }

    val totalCount = new AtomicInteger(rootModules.size)
    val resolvedCount = new AtomicInteger(0)

    def addMoreModules(n: Int) = IO(totalCount.addAndGet(n)).flatMap {
      total =>
        val progress = resolvedCount.get.toDouble / total
        statusUpdates.publish1(UpdatedTasks(List(taskInfo.copy(progress = (progress * 255).toByte))))
    }

    def resolveModules(n: Int) = IO(resolvedCount.addAndGet(n)).flatMap {
      resolved =>
        val progress = resolved.toDouble / totalCount.get
        statusUpdates.publish1(UpdatedTasks(List(taskInfo.copy(progress = (progress * 255).toByte))))
    }

    def countingFetcher(fetcher: ResolutionProcess.Fetch[IO]): ResolutionProcess.Fetch[IO] = {
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
      .handleErrorWith(recover)
  }

  private def repos(repositories: List[RepositoryConfig]): Either[Throwable, List[Repository]] = repositories.collect {
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


  private def resolveCoursierDependencies(
    resolution: Resolution,
    taskInfo: TaskInfo,
    statusUpdates: Publish[IO, KernelStatusUpdate],
    maxIterations: Int = 100
  ) = {
    // check whether we care about a missing resolution
    def shouldErrorIfMissing(mv: (Module, String)): Boolean = {
      resolution.rootDependencies.exists(dep => dep.module == mv._1 && dep.version == mv._2)
    }

    def publish(name: String, progress: Double) = statusUpdates.publish1(UpdatedTasks(List(TaskInfo(name, name.split('/').last, name, TaskStatus.Running, (progress * 255).toByte))))
    def complete(name: String) = statusUpdates.publish1(UpdatedTasks(List(TaskInfo(name, "", "", TaskStatus.Complete, 255.toByte))))
    val cache = this.cache.withLogger {
      new CacheLogger {
        private var totalArtifacts: Int = 0
        private val knownLength = new ConcurrentHashMap[String, Long]()
        override def init(sizeHint: Option[Int]): Unit = totalArtifacts = sizeHint.getOrElse(resolution.minDependencies.size)

        override def downloadingArtifact(url: String): Unit = publish(url, 0.0).unsafeRunAsyncAndForget()
        override def downloadLength(url: String, totalLength: Long, alreadyDownloaded: Long, watching: Boolean): Unit = {
          knownLength.put(url, totalLength)
          if (watching && alreadyDownloaded < totalLength) {
            publish(url, alreadyDownloaded.toDouble / totalLength).unsafeRunAsyncAndForget()
          }
        }

        override def downloadProgress(url: String, downloaded: Long): Unit = {
          if (knownLength.containsKey(url)) {
            val len = knownLength.get(url)
            if (downloaded < len) {
              publish(url, downloaded.toDouble / len).unsafeRunAsyncAndForget()
            } else {
              complete(url).unsafeRunAsyncAndForget()
            }
          }
        }

        override def downloadedArtifact(url: String, success: Boolean): Unit = {
          complete(url).unsafeRunAsyncAndForget()
        }
      }
    }

    Artifacts(cache).withResolution(resolution).withMainArtifacts(true).io.map {
      artifacts => artifacts.toList.map {
        case (artifact, file) => artifact.url -> IO.pure(file)
      }
    }
  }

  override protected def resolveDependencies(
    repositories: List[RepositoryConfig],
    dependencies: List[String],
    exclusions: List[String]
  ): IO[List[(String, IO[File])]] = for {
    repos <- IO.fromEither(repos(repositories))
    res   <- resolution(dependencies, exclusions, repos, statusUpdates, taskInfo)
    files <- resolveCoursierDependencies(res, taskInfo, statusUpdates)
  } yield files

  protected def cacheLocation(uri: URI): Path = {
    val pathParts = Seq(uri.getScheme, uri.getAuthority, uri.getPath).flatMap(Option(_)) // URI methods sometimes return `null`, great.
    coursier.cache.CacheDefaults.location.toPath.resolve(Paths.get(pathParts.head, pathParts.tail: _*))
  }
}
object CoursierFetcher {

  object Factory extends DependencyManagerFactory[IO] {
    override def apply(path: String, taskInfo: TaskInfo, statusUpdates: Publish[IO, KernelStatusUpdate]): DependencyManager[IO] = new CoursierFetcher(path, taskInfo, statusUpdates)
  }
}