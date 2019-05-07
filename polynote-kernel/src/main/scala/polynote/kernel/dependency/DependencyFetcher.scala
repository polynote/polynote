package polynote.kernel.dependency

import java.io._
import java.net.URI
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.Executors

import cats.effect.{ContextShift, IO}
import cats.syntax.alternative._
import cats.syntax.apply._
import cats.syntax.either._
import cats.instances.either._
import cats.instances.list._
import polynote.config.{DependencyConfigs, RepositoryConfig}
import polynote.kernel._
import polynote.kernel.util.{DownloadableFileProvider, Publish}
import polynote.messages.{TinyList, TinyMap}

import scala.concurrent.ExecutionContext

trait DependencyFetcher[F[_]] {

  def fetchDependencyList(
    repositories: List[RepositoryConfig],
    dependencies: List[DependencyConfigs],
    exclusions: List[String],
    taskInfo: TaskInfo,
    statusUpdates: Publish[F, KernelStatusUpdate]
  ): F[List[(String, F[File])]]

}

trait URLDependencyFetcher extends DependencyFetcher[IO] {
  protected implicit def executionContext: ExecutionContext
  protected implicit def contextShift: ContextShift[IO]

  /**
    * Split the dependencies into actual dependency coordinates vs direct URLs
    */
  protected def splitDependencies(deps: List[DependencyConfigs]): (List[DependencyConfigs], List[URI]) = {
    val (dependencies, uriList) = deps.flatMap { dep =>
      dep.toList.collect {
        case (k, v) =>
          val (dependencies, uris) = v.map { s =>
            val asURI = new URI(s)

            Either.cond(
              // Do we support this protocol (if any?)
              DownloadableFileProvider.isSupported(asURI),
              asURI,
              s // an unsupported protocol might be a dependency
            )
          }.separate

          (TinyMap(k -> TinyList(dependencies)), uris)
      }
    }.unzip

    (dependencies, uriList.flatten)
  }

  /**
    * Given a URI, return the local Path to which it should cache/download
    */
  protected def cacheLocation(uri: URI): Path

  protected def fetchUrl(uri: URI, localFile: File, statusUpdates: Publish[IO, KernelStatusUpdate], chunkSize: Int = 8192): (String, IO[File]) = {
    def withProgress(s: fs2.Stream[IO, Byte], uri: String, fileSize: Long, taskInfo: TaskInfo): fs2.Stream[IO, Byte] = {
      val size = if (fileSize > 0) fileSize else 300 * 1024 * 1024 // this is roughly the size of the largest uber jars we have

      // is there a better way to do this? seems more convoluted than necessary but I couldn't quite get there...
      // in practice all this gobbledygook doesn't seem to slow things down though, so that's good.
      s.chunks
        .mapAccumulate(0)((n, c) => (n + c.size, c)) // carry along the size of the chunks so far
        .evalTap { case (i, _) =>
        statusUpdates.publish1(UpdatedTasks(taskInfo.copy(progress = (i.toDouble * 255 / size).toByte) :: Nil))
      }.flatMap { case (_, c) =>
        fs2.Stream.chunk(c) // this looks like the only way to re-chunk the Stream...
      }
    }

    val uriStr = uri.toString
    val taskInfo = TaskInfo(uriStr, s"Downloading $uriStr", uriStr, TaskStatus.Running)
    val dlIO = IO.fromEither(Either.fromOption(DownloadableFileProvider.getFile(uri), new Exception(s"Unable to find provider for uri $uri"))).flatMap { file =>
      // try to short circuit - maybe it's on the local FS?
      val inputAsFile = Paths.get(uri.getPath).toFile
      // if not, maybe it's already been cached?
      val cacheFile = localFile

      if (inputAsFile.exists()) {
        IO.pure(inputAsFile)
      } else if (cacheFile.exists() && !Option(uri.getQuery).exists(_.contains("nocache"))) {
        IO.pure(cacheFile)
      } else {
        // ok we actually have to fetch it I guess
        (for {
          _ <- IO(Files.createDirectories(cacheFile.toPath.getParent))
          os = new FileOutputStream(cacheFile)
          // is it overkill to use fs2 steams for this...?
          fs2IS = fs2.io.readInputStream[IO](file.openStream, chunkSize, executionContext)
          fs2OS = fs2.io.writeOutputStream[IO](IO(os), executionContext)
          fileSize <- file.size
          _ <- withProgress(fs2IS, uriStr, fileSize, taskInfo).through(fs2OS).compile.drain
          _ <- statusUpdates.publish1(UpdatedTasks(List(taskInfo.copy(status = TaskStatus.Complete, progress = 255.toByte))))
        } yield cacheFile).handleErrorWith { t: Throwable =>
          IO.raiseError(t).guarantee(IO {
            if (cacheFile.exists())
              Files.delete(cacheFile.toPath) // clean up
          } *> statusUpdates.publish1(UpdatedTasks(List(taskInfo.copy(status = TaskStatus.Error)))))
        }
      }
    }

    uri.toString -> dlIO
  }

  /**
    * Fetch the given URI dependencies, after computing their local file paths
    */
  protected def fetchUrls(
    uris: List[URI],
    statusUpdates: Publish[IO, KernelStatusUpdate],
    chunkSize: Int = 8192
  ): List[(String, IO[File])] = uris.map {
    uri =>
      val localFile = cacheLocation(uri)
      fetchUrl(uri, localFile.toFile, statusUpdates, chunkSize)
  }

  /**
    * Resolve the actual dependency coordinates, and return tasks which will download the files while reporting on
    * progress
    */
  protected def resolveDependencies(
    repositories: List[RepositoryConfig],
    dependencies: List[DependencyConfigs],
    exclusions: List[String],
    taskInfo: TaskInfo,
    statusUpdates: Publish[IO, KernelStatusUpdate]
  ): IO[List[(String, IO[File])]]

  def fetchDependencyList(
    repositories: List[RepositoryConfig],
    dependencies: List[DependencyConfigs],
    exclusions: List[String],
    taskInfo: TaskInfo,
    statusUpdates: Publish[IO, KernelStatusUpdate]
  ): IO[List[(String, IO[File])]] = {
    val (deps, urls) = splitDependencies(dependencies)
    val downloadFiles = fetchUrls(urls, statusUpdates)
    resolveDependencies(repositories, deps, exclusions, taskInfo, statusUpdates).map {
      resolved => downloadFiles ::: resolved
    }
  }

}