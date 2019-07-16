package polynote.kernel.dependency

import java.io._
import java.net.URI
import java.nio.file.{Files, Path, Paths}

import cats.effect.concurrent.Ref
import cats.effect.{ContextShift, IO}
import cats.instances.either._
import cats.instances.list._
import cats.syntax.alternative._
import cats.syntax.apply._
import cats.syntax.either._
import cats.syntax.parallel._
import polynote.config.RepositoryConfig
import polynote.kernel._
import polynote.kernel.util.{DownloadableFileProvider, Publish}

import scala.concurrent.ExecutionContext

trait ScalaDependencyFetcher extends DependencyManager[IO] {
  protected implicit def executionContext: ExecutionContext
  protected implicit def contextShift: ContextShift[IO]

  /**
    * Split the dependencies into actual dependency coordinates vs direct URLs
    */
  protected def splitDependencies(deps: List[String]): (List[String], List[URI]) = {
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
    dependencies: List[String],
    exclusions: List[String]
  ): IO[List[(String, IO[File])]]

  def getDependencyProvider(
    repositories: List[RepositoryConfig],
    dependencies: List[String],
    exclusions: List[String]
  ): IO[DependencyProvider] = for {
    deps <- fetchDependencyList(repositories, dependencies, exclusions)
    fetched <- downloadDependencies(deps)
  } yield new ClassLoaderDependencyProvider(fetched)

  def fetchDependencyList(
    repositories: List[RepositoryConfig],
    dependencies: List[String],
    exclusions: List[String]
  ): IO[List[(String, IO[File])]] = {
    val (deps, urls) = splitDependencies(dependencies)
    val downloadFiles = fetchUrls(urls, statusUpdates)
    resolveDependencies(repositories, deps, exclusions).map {
      resolved => downloadFiles ::: resolved
    }
  }

  def downloadDependencies(deps: List[(String, IO[File])]): IO[List[(String, File)]] = {
    val completedCounter = Ref.unsafe[IO, Int](0)
    val numDeps = deps.size
    deps.map {
      case (name, ioFile) => for {
        download     <- ioFile.start
        file         <- download.join
        _            <- completedCounter.update(_ + 1)
        numCompleted <- completedCounter.get
        statusUpdate  = taskInfo.copy(detail = s"Downloaded $numCompleted / $numDeps", progress = ((numCompleted.toDouble * 255) / numDeps).toByte)
        _            <- statusUpdates.publish1(UpdatedTasks(statusUpdate :: Nil))
      } yield (name, file)
    }.map(_.map(Some(_)).handleErrorWith(downloadFailed)).parSequence.map(_.flatten)
  }

  // TODO: ignoring download errors for now, until the weirdness of resolving nonexisting artifacts is solved
  private def downloadFailed(err: Throwable): IO[Option[(String, File)]] = IO {
    err match {
      case other =>
        // don't ignore other errors
        throw RuntimeError(new Exception(s"Error while downloading dependencies: ${other.getMessage}", other))
    }
  }
}
