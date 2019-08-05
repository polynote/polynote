package polynote.kernel.dependency

import java.io.File
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

import cats.effect.{ContextShift, IO}
import cats.instances.list._
import cats.syntax.apply._
import cats.syntax.parallel._
import org.apache.ivy.Ivy
import org.apache.ivy.core.module.descriptor.{Artifact, DefaultDependencyDescriptor, DefaultModuleDescriptor}
import org.apache.ivy.core.module.id.ModuleRevisionId
import org.apache.ivy.core.report.DownloadReport
import org.apache.ivy.core.resolve.{DownloadOptions, ResolveOptions}
import org.apache.ivy.core.settings.IvySettings
import org.apache.ivy.plugins.namespace.NameSpaceHelper
import org.apache.ivy.plugins.repository._
import org.apache.ivy.plugins.repository.file.FileResource
import org.apache.ivy.plugins.repository.url.URLResource
import org.apache.ivy.plugins.resolver.util.ResolvedResource
import org.apache.ivy.plugins.resolver.{CacheResolver, ChainResolver, IBiblioResolver, URLResolver}
import org.apache.ivy.util.filter.{Filter => IvyFilter}
import polynote.config.{PolyLogger, RepositoryConfig, ivy, maven}
import polynote.kernel.util.{Publish, newDaemonThreadPool}
import polynote.kernel.{KernelStatusUpdate, TaskInfo, TaskStatus, UpdatedTasks}

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext

class IvyFetcher(val path: String, val taskInfo: TaskInfo, val statusUpdates: Publish[IO, KernelStatusUpdate]) extends ScalaDependencyFetcher {
  protected implicit val executionContext: ExecutionContext = ExecutionContext.fromExecutorService(newDaemonThreadPool("ivy"))
  protected implicit val contextShift: ContextShift[IO] =  IO.contextShift(executionContext)

  private val logger = new PolyLogger

  private val settings = new IvySettings()
  private val cachePath = settings.getDefaultCache.toPath

  override protected def resolveDependencies(
    repositories: List[RepositoryConfig],
    dependencies: List[String],
    exclusions: List[String]
  ): IO[List[(String, IO[File])]] = {

    val settings = createSettings(repositories, statusUpdates)
    val ivy = Ivy.newInstance(settings)
    ivy.getEventManager.addTransferListener(new TransferListener {
      def transferProgress(evt: TransferEvent): Unit = {
        if (evt.isTotalLengthSet) {
          val totalLength = evt.getTotalLength
          val downloaded = evt.getLength
          val status = if (downloaded >= totalLength) TaskStatus.Complete else TaskStatus.Running
          statusUpdates.publish1(UpdatedTasks(
            List(TaskInfo(
              evt.getResource.getName,
              s"Downloading ${evt.getLocalFile.getName}",
              "",
              status,
              (downloaded.toDouble / totalLength * 255).toByte)))).unsafeRunAsyncAndForget()
        }
      }
    })

    val updateProgress = (progress: Double) => statusUpdates.publish1(UpdatedTasks(List(taskInfo.copy(progress = (progress * 255).toByte))))

    resolve(ivy, dependencies, exclusions, updateProgress)
  }


  private def createSettings(repositories: List[RepositoryConfig], statusUpdates: Publish[IO, KernelStatusUpdate]) = {
    val chain = new ChainResolver()
    chain.setName("Resolver chain")

    val cacheResolver = new CacheResolver(settings)
    chain.add(cacheResolver)

    val resolvers = repositories.collect {
      case repo@ivy(base, _, _, changing) =>
        val resolver = new ParallelURLResolver(statusUpdates)
        val normedBase = base.stripSuffix("/") + "/"
        resolver.addArtifactPattern(normedBase + repo.artifactPattern.stripPrefix("/"))
        resolver.addIvyPattern(normedBase + repo.metadataPattern.stripPrefix("/"))
        resolver.setName(base)
        resolver
      case repo@maven(base, changing) =>
        val resolver = new ParallelIBiblioResolver(statusUpdates)
        resolver.setRoot(base)
        resolver.setM2compatible(true)
        resolver.setName(base)
        resolver
    }

    // add maven central to the end of the chain
    val mavenCentral = new ParallelIBiblioResolver(statusUpdates)
    mavenCentral.setName("Maven central")
    mavenCentral.setRoot(IBiblioResolver.DEFAULT_M2_ROOT)
    mavenCentral.setM2compatible(true)

    chain.add(mavenCentral)

    resolvers foreach chain.add

    settings.addResolver(chain)
    settings.setDefaultResolver(chain.getName)

    settings
  }

  private def resolve(
    ivy: Ivy,
    dependencies: List[String],
    exclusions: List[String],
    updateProgress: Double => IO[Unit]): IO[List[(String, IO[File])]] = {

    val excludedOrgModules = exclusions.map {
      e => e.split(':') match {
        case Array(org) => org -> "*"
        case Array(org, module) => org -> module
      }
    }.groupBy(_._1).mapValues(_.map(_._2).toSet) ++ Map("org.scala-lang" -> Set("*"), "org.apache.spark" -> Set("*"))

    val baseFilter = Filter {
      case artifact if excludedOrgModules contains artifact.getModuleRevisionId.getOrganisation =>
        val excluded = excludedOrgModules(artifact.getModuleRevisionId.getOrganisation)
        !(excluded(artifact.getModuleRevisionId.getModuleId.getName) || excluded("*"))
      case _ => true
    }

    val defaultOptions = new ResolveOptions()
    //defaultOptions.setLog("quiet")
    defaultOptions.setDownload(true)
    defaultOptions.setTransitive(true)
    defaultOptions.setConfs(Array("default", "compile"))
    defaultOptions.setArtifactFilter(baseFilter)


    def mdOpts(org: String, name: String, ver: String, classifier: String = "", typ: String = "jar", config: String = "default") = {
      val attrs = Map(
        "classifier" -> classifier,
        "type" -> typ
      )

      val mrid = ModuleRevisionId.newInstance(org, name, ver, attrs.asJava)

      (mrid, (classifier != "all"), Option(config).getOrElse("default"))
    }

    val ivyDeps = dependencies.map {
      moduleStr => moduleStr.split(':') match {
        case Array(org, name, ver) => mdOpts(org, name, ver)
        case Array(org, name, classifier, ver) => mdOpts(org, name, ver, classifier)
        case Array(org, name, typ, classifier, ver) => mdOpts(org, name, ver, classifier, typ)
        case Array(org, name, typ, config, classifier, ver) => mdOpts(org, name, ver, classifier, typ, config)
        case _ => throw new Exception(s"Unable to parse dependency '$moduleStr'")
      }
    }

    val downloaded = new AtomicInteger(0)
    val total = ivyDeps.size

    val ivyModule = new DefaultModuleDescriptor(ModuleRevisionId.newInstance("caller", "all-caller", "working"), "integration", null, true)
    org.apache.ivy.plugins.parser.m2.PomModuleDescriptorBuilder.MAVEN2_CONFIGURATIONS.foreach(ivyModule.addConfiguration)

    ivyDeps.foreach {
      case (mrid, transitive, config) =>
        val dd = new DefaultDependencyDescriptor(ivyModule, mrid, mrid, true, false, transitive)
        dd.addDependencyConfiguration("default", config)
        ivyModule.addDependency(dd)
    }

    (IO(println(s"Starting resolve at ${java.time.Instant.now()}")) *>
      IO(ivy.resolve(ivyModule, defaultOptions)) <* IO(println(s"Finished resolve at ${java.time.Instant.now()}"))
    ).map {
      report =>

        // TODO: handle errors
        if (report.hasError) {
          report.getAllProblemMessages.asScala.foreach {
            msg => logger.error(s"Resolution error: $msg")
          }
        }

        report.getArtifactsReports(null, false).toList.flatMap {
          artifactReport =>
            val localFile = Option(artifactReport.getLocalFile)
            if (localFile.isEmpty) {
              logger.error(s"Local file is null for artifact ${artifactReport.getArtifact}, download ${artifactReport.getDownloadStatus}")
            }
            localFile.map {
              file => file.getName -> IO.pure(file)
            }
        }.toMap.toList
    }
  }

  case class Filter(fn: Artifact => Boolean) extends IvyFilter {
    override def accept(o: Any): Boolean = o match {
      case artifact: Artifact => fn(artifact)
      case _ => false
    }

    def &&(next: Artifact => Boolean): Filter = copy(fn = artifact => fn(artifact) && next(artifact))
    def ||(next: Artifact => Boolean): Filter = copy(fn = artifact => fn(artifact) || next(artifact))
  }

  protected def cacheLocation(uri: URI): Path = {
    val pathParts = Seq(uri.getScheme, uri.getAuthority, uri.getPath).flatMap(Option(_))
    pathParts.foldLeft(cachePath)(_ resolve _)
  }


  class ParallelURLResolver(statusUpdates: Publish[IO, KernelStatusUpdate]) extends URLResolver {
    // weird protected access problems when trying to put this stuff into a trait, so it's duplicated in both of these subclasses.

    private val artifactResourceResolver = new ArtifactResourceResolver() {
      override def resolve(artifact: Artifact): ResolvedResource =
        getArtifactRef(NameSpaceHelper.transform(artifact, getNamespace.getFromSystemTransformer), null)
    }

    private val resourceDownloader = new ResourceDownloader {
      def download(artifact: Artifact, resource: Resource, dest: File): Unit = {
        fetchUrl(new URI(resource.getName), dest, statusUpdates) match {
          case (name, io) => io.unsafeRunSync()
        }
      }
    }

    override def download(artifacts: Array[Artifact], options: DownloadOptions): DownloadReport = {
      val eventManager = getEventManager
      val cacheManager = getRepositoryCacheManager
      clearArtifactAttempts()
      if (eventManager != null) {
        getRepository.addTransferListener(eventManager)
      }

      try {
        val downloads = artifacts.toList.map {
          artifact =>
            IO(cacheManager.download(artifact, artifactResourceResolver, resourceDownloader, getCacheDownloadOptions(options)))
        }
        val report = new DownloadReport
        downloads.parSequence.unsafeRunSync().foreach(report.addArtifactReport)
        report
      } finally {
        if (eventManager != null) {
          getRepository.removeTransferListener(eventManager)
        }
      }
    }
  }

  class ParallelIBiblioResolver(statusUpdates: Publish[IO, KernelStatusUpdate]) extends IBiblioResolver {
    private val artifactResourceResolver = new ArtifactResourceResolver() {
      override def resolve(artifact: Artifact): ResolvedResource =
        getArtifactRef(NameSpaceHelper.transform(artifact, getNamespace.getFromSystemTransformer), null)
    }

    private val resourceDownloader = new ResourceDownloader {
      def download(artifact: Artifact, resource: Resource, dest: File): Unit = {
        val uri = resource match {
          case res: URLResource => Option(res.getURL).map(_.toURI)
          case res: FileResource => Option(res.getFile).map(_.toURI)
          case _ => Option(resource.getName).flatMap(name => try Some(new URI(name)) catch { case err: Throwable => None } )
        }
        uri.foreach {
          uri => fetchUrl(uri, dest, statusUpdates) match {
            case (name, io) => io.unsafeRunSync()
          }
        }
      }
    }

    override def download(artifacts: Array[Artifact], options: DownloadOptions): DownloadReport = {
      val eventManager = getEventManager
      if (eventManager != null) {
        getRepository.addTransferListener(eventManager)
      }
      try {
        ensureConfigured(getSettings)
        val cacheManager = getRepositoryCacheManager
        clearArtifactAttempts()
        val downloads = artifacts.toList.map {
          artifact =>
            IO(cacheManager.download(artifact, artifactResourceResolver, resourceDownloader, getCacheDownloadOptions(options)))
        }
        val report = new DownloadReport
        downloads.parSequence.unsafeRunSync().foreach(report.addArtifactReport)
        report
      } finally {
        if (eventManager != null) {
          getRepository.removeTransferListener(eventManager)
        }
      }
    }
  }
}
object IvyFetcher {

  object Factory extends DependencyManagerFactory[IO] {
    override def apply(path: String, taskInfo: TaskInfo, statusUpdates: Publish[IO, KernelStatusUpdate]): DependencyManager[IO] = new IvyFetcher(path, taskInfo, statusUpdates)
  }
}
