package polynote.server.repository

import java.io.{File, FileNotFoundException}
import java.nio.charset.StandardCharsets
import java.nio.file.{FileAlreadyExistsException, FileVisitOption, Files, Path}

import cats.implicits._
import io.circe.Printer
import polynote.config.{Mount, PolynoteConfig}
import polynote.kernel.environment.{Config, Env}
import polynote.kernel.{BaseEnv, GlobalEnv}
import polynote.kernel.util.RefMap
import polynote.messages._
import polynote.server.repository.ipynb.ZeppelinNotebook
import zio.{RIO, Task, ZIO}
import zio.blocking.Blocking
import zio.interop.catz._

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext

trait NotebookRepository {

  /**
    * @return Whether a notebook exists at the specified path
    */
  def notebookExists(path: String): RIO[BaseEnv with GlobalEnv, Boolean]

  /**
    * @return The location of the notebook on disk (absolute path)
    */
  def notebookLoc(path: String): RIO[BaseEnv with GlobalEnv, String]

  /**
    * @return The notebook at the specified path
    */
  def loadNotebook(path: String): RIO[BaseEnv with GlobalEnv, Notebook]

  /**
    * Save the given notebook to the specified path
    */
  def saveNotebook(path: String, cells: Notebook): RIO[BaseEnv with GlobalEnv, Unit]

  /**
    * @return A list of notebook paths that exist in this repository
    */
  def listNotebooks(): RIO[BaseEnv with GlobalEnv, List[String]]

  /**
    * Create a notebook at the given path, optionally creating it from the given content string.
    *
    * TODO: Server no longer imports. Need to implement this on the client side.
    */
  def createNotebook(path: String, maybeContent: Option[String]): RIO[BaseEnv with GlobalEnv, String]

  def renameNotebook(path: String, newPath: String): RIO[BaseEnv with GlobalEnv, String]

  def deleteNotebook(path: String): RIO[BaseEnv with GlobalEnv, Unit]

  /**
    * Initialize the storage for this repository (i.e. create directory if it doesn't exist)
    */
  def initStorage(): RIO[BaseEnv with GlobalEnv, Unit]
}

/**
  * Notebook Repository that understands Storage Mounts [[polynote.config.Mount]].
  *
  * Multiplexes multiple Repositories - one for each mount point - based on the notebooks path prefix.
  *
  * @param repos:  Map of mount point -> repository
  * @param mkRepo: How to create a new repository that will handle a specific mount point.
  */
class MountAwareRepository (
  repos: RefMap[String, NotebookRepository],
  mkRepo: (Path, PolynoteConfig, ExecutionContext) => NotebookRepository
) extends NotebookRepository {

  val baseMount: RIO[Config, Mount] = for {
    config <- Config.access
  } yield config.storage.mounts.head

  def mountFromPath(notebookPath: String): RIO[BaseEnv with GlobalEnv, Mount] = for {
    config <- Config.access
    mount  <- ZIO.fromOption(config.storage.mounts.find(mount => notebookPath.startsWith(mount.src)))
//        .mapError(_ => new FileNotFoundException(s"Unable to find mount for $notebookPath, available mounts: ${config.storage.mounts}"))
      .catchAll(_ => baseMount) // if it's unprefixed, we default to the base mount.
  } yield mount

  def getOrCreateRepo(notebookPath: String): RIO[BaseEnv with GlobalEnv, (NotebookRepository, Mount)] = {
    for {
      config <- Config.access
      mount  <- mountFromPath(notebookPath)
      repo   <- repos.getOrCreate(mount.src) {
        for {
          blocking <- ZIO.accessM[Blocking](_.blocking.blockingExecutor)
          r        = mkRepo(new File(sys.props("user.dir")).toPath.resolve(mount.src), config, blocking.asEC)
          _        <- r.initStorage()
        } yield r
      }
    } yield (repo, mount)
  }

  private def relativize(mount: Mount, path: String) = {
    val mountPath = new File(mount.src).toPath
    val nbPath = new File(path).toPath
    if (nbPath.startsWith(mountPath)) mountPath.relativize(nbPath).toString else path
  }

  private def delegate[T](originalPath: String)(f: (NotebookRepository, String, Mount) => RIO[BaseEnv with GlobalEnv, T]): RIO[BaseEnv with GlobalEnv, T] = {
    getOrCreateRepo(originalPath).flatMap {
      case (repo, mount) =>
        val relativePath = relativize(mount, originalPath)
        f(repo, relativePath, mount)
    }
  }

  override def notebookExists(originalPath: String): RIO[BaseEnv with GlobalEnv, Boolean] = delegate(originalPath) {
    (repo, relativePath, _) => repo.notebookExists(relativePath)
  }

  // Because notebookLoc is an absolute path we don't modify it here (in other cases)
  override def notebookLoc(originalPath: String): RIO[BaseEnv with GlobalEnv, String] = delegate(originalPath) {
    (repo, relativePath, _) =>
      repo.notebookLoc(relativePath)
  }

  override def loadNotebook(originalPath: String): RIO[BaseEnv with GlobalEnv, Notebook] = delegate(originalPath) {
    (repo, relativePath, mount) =>
     for {
       nb <- repo.loadNotebook(relativePath)
       baseMount <- baseMount
     } yield if (mount == baseMount) nb else nb.copy(path = s"${mount.src}/${nb.path}")
  }

  override def saveNotebook(originalPath: String, cells: Notebook): RIO[BaseEnv with GlobalEnv, Unit] = delegate(originalPath) {
    (repo, relativePath, _) => repo.saveNotebook(relativePath, cells)
  }

  override def listNotebooks(): RIO[BaseEnv with GlobalEnv, List[String]] = {
    for {
      config    <- Config.access
      mounts    <- config.storage.mounts.map(_.src).map(getOrCreateRepo).sequence
      baseMount <- baseMount
      notebooks <- mounts.map {
        case (repo, mount) =>
          if (mount == baseMount) {
            repo.listNotebooks()
          } else {
            repo.listNotebooks().map(_.map(nbPath => s"${mount.src}/$nbPath"))
          }
      }.sequence
    } yield notebooks.flatten
  }

  override def createNotebook(originalPath: String, maybeContent: Option[String]): RIO[BaseEnv with GlobalEnv, String] = delegate(originalPath) {
    (repo, relativePath, mount) =>
        for {
          nbPath <- repo.createNotebook(relativePath, maybeContent)
          baseMount <- baseMount
        } yield if (mount == baseMount) nbPath else s"${mount.src}/${nbPath}"
  //      repo.createNotebook(relativePath, maybeContent).map(nbPath => s"${mount.src}/$nbPath")
  }

  override def renameNotebook(path: String, newPath: String): RIO[BaseEnv with GlobalEnv, String] = {
    def rename(oldMount: Mount, newMount: Mount): RIO[BaseEnv with GlobalEnv, String] = {
      if (oldMount.src == newMount.src) { // same repo, just use the default rename
        getOrCreateRepo(path).flatMap {
          case (repo, mount) =>
            val oldRelPath = relativize(mount, path)
            val newRelPath = relativize(mount, newPath)
            for {
              renamed <- repo.renameNotebook(oldRelPath, newRelPath)
              baseMount <- baseMount
            } yield if (mount == baseMount) renamed else s"${mount.src}/$renamed"
        }
      } else { // different repos, so we need to do more stuff
        for {
          (oldRepo, _) <- getOrCreateRepo(path)
          oldPath      = relativize(oldMount, path)
          oldNB        <- oldRepo.loadNotebook(oldPath)
          (newRepo, _) <- getOrCreateRepo(newPath)
          newRelPath   = relativize(newMount, newPath)
          _            <- newRepo.saveNotebook(newRelPath, oldNB)
          _            <- oldRepo.deleteNotebook(oldPath) // only delete old after the new one was successfully saved
          baseMount    <- baseMount
        } yield if (newMount == baseMount) newRelPath else s"${newMount.src}/$newRelPath"
      }
    }


    for {
      oldMount  <- mountFromPath(path)
      newMount  <- mountFromPath(newPath)
      result    <- rename(oldMount, newMount)
    } yield result
  }

  override def deleteNotebook(originalPath: String): RIO[BaseEnv with GlobalEnv, Unit] = delegate(originalPath) {
    (repo, relativePath, _) => repo.deleteNotebook(relativePath)
  }

  override def initStorage(): RIO[BaseEnv with GlobalEnv, Unit] = for {
    config <- Config.access
    _      <- config.storage.mounts.map(_.src).map(getOrCreateRepo).sequence
  } yield ()
}

abstract class FileBasedRepository extends NotebookRepository {
  def path: Path
  def chunkSize: Int
  def executionContext: ExecutionContext
  def config: PolynoteConfig

  protected def pathOf(relativePath: String): Path = path.resolve(relativePath)

  protected def loadString(path: String): RIO[BaseEnv with GlobalEnv, String] = for {
    content <- readBytes(Files.newInputStream(pathOf(path)), chunkSize, executionContext)
  } yield new String(content.toArray, StandardCharsets.UTF_8)

  def writeString(relativePath: String, content: String): RIO[BaseEnv with GlobalEnv, Unit] = ZIO {
    val nbPath = pathOf(relativePath)

    if (nbPath.getParent != this.path) {
      Files.createDirectories(nbPath.getParent)
    }

    Files.write(pathOf(relativePath), content.getBytes(StandardCharsets.UTF_8))
  }.map(_ => ())

  protected def defaultExtension: String

  protected def validNotebook(path: Path): Boolean = path.toString.endsWith(s".$defaultExtension")
  protected def maxDepth: Int = 4

  def listNotebooks(): RIO[BaseEnv with GlobalEnv, List[String]] =
    ZIO(Files.walk(path, maxDepth, FileVisitOption.FOLLOW_LINKS).iterator().asScala.drop(1).filter(validNotebook).toList).map {
      paths => paths.map {
        path => this.path.relativize(path).toString
      }
    }

  def notebookExists(path: String): RIO[BaseEnv with GlobalEnv, Boolean] = {
    val repoPath = this.path.resolve(path)
    ZIO(repoPath.toFile.exists())
  }

  def notebookLoc(path: String): RIO[BaseEnv with GlobalEnv, String] = {
    val repoPath = this.path.resolve(path)
    ZIO(repoPath.toAbsolutePath.toString)
  }

  val EndsWithNum = """^(.*)(\d+)$""".r

  def findUniqueName(path: String, ext: String): RIO[BaseEnv with GlobalEnv, String] = {
    notebookExists(path + ext).flatMap {
      case true =>
        path match {
          case EndsWithNum(base, num) =>
            findUniqueName(s"$base${num.toInt + 1}", ext)
          case _ =>
            findUniqueName(s"${path}2", ext) // start at two because the first one is implicitly #1? Or is that weird?
        }
      case false =>
        ZIO.succeed(path)
    }
  }

  def relativeDepth(relativePath: String): Int = {

    val fullPath = pathOf(relativePath).iterator().asScala
    val nbPath = path.iterator().asScala

    fullPath.dropWhile(elem => nbPath.contains(elem)).length
  }

  def emptyNotebook(path: String, title: String): Notebook = Notebook(
    ShortString(path),
    ShortList(
      NotebookCell(0, "text", s"# $title\n\nThis is a text cell. Start editing!") :: Nil
    ),
    Some(NotebookConfig.fromPolynoteConfig(config))
  )

  def createNotebook(relativePath: String, maybeContent: Option[String] = None): RIO[BaseEnv with GlobalEnv, String] = {
    val ext = s".$defaultExtension"
    val noExtPath = relativePath.replaceFirst("""^/+""", "").stripSuffix(ext)

    if (relativeDepth(relativePath) > maxDepth) {
      ZIO.fail(new IllegalArgumentException(s"Input path ($relativePath) too deep, maxDepth is $maxDepth"))
    } else {
      findUniqueName(noExtPath, ext).flatMap { name =>
        val extPath = name + ext
        val createOrImport = maybeContent match {
          case None =>
            val defaultTitle = name.split('/').last.replaceAll("[\\s\\-_]+", " ").trim()
            saveNotebook(extPath, emptyNotebook(extPath, defaultTitle))
          case Some(content) =>
            if (relativePath.endsWith(".json")) { // assume zeppelin
              import io.circe.parser.parse
              import io.circe.syntax._
              for {
                parsed <- ZIO.fromEither(parse(content))
                  zep <- ZIO.fromEither(parsed.as[ZeppelinNotebook])
                  jup = zep.toJupyterNotebook
                  jupStr = Printer.spaces2.copy(dropNullValues = true).pretty(jup.asJson)
                  io <- writeString(extPath, jupStr)
              } yield io
            } else {
              writeString(extPath, content)
            }
        }
        createOrImport.map(_ => extPath)
      }
    }
  }

  def renameNotebook(oldPath: String, newPath: String): RIO[BaseEnv with GlobalEnv, String] = {
    val ext = s".$defaultExtension"
    val withExt = newPath.replaceFirst("""^/+""", "").stripSuffix(ext) + ext

    if (relativeDepth(withExt) > maxDepth) {
      ZIO.fail(new IllegalArgumentException(s"Input path ($newPath) too deep, maxDepth is $maxDepth"))
    } else {
      (notebookExists(oldPath), notebookExists(withExt)).mapN(_ -> _).flatMap {
        case (false, _)    => ZIO.fail(new FileNotFoundException(s"File $oldPath doesn't exist"))
        case (_, true)     => ZIO.fail(new FileAlreadyExistsException(s"File $withExt already exists"))
        case (true, false) =>
          val absOldPath = pathOf(oldPath)
          val absNewPath = pathOf(withExt)
          ZIO {
            Files.move(absOldPath, absNewPath)
          }.as(withExt)
      }
    }
  }

  // TODO: should probably have a "trash" or something instead – a way of recovering a file from accidental deletion?
  def deleteNotebook(path: String): RIO[BaseEnv with GlobalEnv, Unit] = {
    notebookExists(path).flatMap {
      case false => ZIO.fail(new FileNotFoundException(s"File $path does't exist"))
      case true  => ZIO(Files.delete(pathOf(path)))
    }
  }

  def initStorage(): RIO[BaseEnv with GlobalEnv, Unit] = ZIO {
    if (!Files.exists(path)) {
      Files.createDirectories(path)
    }
  }
}
