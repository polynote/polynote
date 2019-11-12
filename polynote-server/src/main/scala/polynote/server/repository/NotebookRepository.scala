package polynote.server.repository

import java.io.{File, FileNotFoundException}
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.{FileAlreadyExistsException, FileVisitOption, Files, Path, Paths}

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
    * @return The location of the notebook (must be absolute)
    */
  def notebookURI(path: String): RIO[BaseEnv with GlobalEnv, Option[URI]]

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
  * Notebook Repository that delegates to child Repositories that correspond to Storage Mounts [[polynote.config.Mount]].
  *
  * @param root:  The repository for this node in the tree
  * @param repos: Map of mount points -> repositories for children of this node
  */
class TreeRepository (
  root: NotebookRepository,
  repos: Map[String, NotebookRepository]
) extends NotebookRepository {

  /**
    * Helper for picking the proper Repository to use for the given notebook path.
    *
    * @param notebookPath: The path to the notebook
    * @param f:            Function for `(NotebookRepository, relativePath: String, maybeBasePath: Option[String) => RIO[Env, T]`.
    *                      The presence of `maybeBasePath` reflects whether the path has been relativized (in case callers need to de-relativize any results)
    */
  private def delegate[T](notebookPath: String)(f: (NotebookRepository, String, Option[String]) => RIO[BaseEnv with GlobalEnv, T]): RIO[BaseEnv with GlobalEnv, T] = {
    val originalPath = Paths.get(notebookPath)
    val basePath = originalPath.getName(0)
    val base = basePath.toString

    val repoForPath = repos.getOrElse(base, root)

    val (relativePath, maybeBasePath) = if (repos.contains(base)) (basePath.relativize(originalPath).toString, Option(base)) else (notebookPath, None)
    f(repoForPath, relativePath.toString, maybeBasePath)
  }

  override def notebookExists(originalPath: String): RIO[BaseEnv with GlobalEnv, Boolean] = delegate(originalPath) {
    (repo, relativePath, _) => repo.notebookExists(relativePath)
  }

  // Because notebookURI is absolute path we don't need to modify it here
  override def notebookURI(originalPath: String): RIO[BaseEnv with GlobalEnv, Option[URI]] = delegate(originalPath) {
    (repo, relativePath, _) =>
      repo.notebookURI(relativePath)
  }

  override def loadNotebook(originalPath: String): RIO[BaseEnv with GlobalEnv, Notebook] = delegate(originalPath) {
    (repo, relativePath, base) =>
     for {
       nb <- repo.loadNotebook(relativePath)
     } yield base.map(b => nb.copy(path = Paths.get(b, nb.path).toString)).getOrElse(nb)
  }

  override def saveNotebook(originalPath: String, cells: Notebook): RIO[BaseEnv with GlobalEnv, Unit] = delegate(originalPath) {
    (repo, relativePath, _) => repo.saveNotebook(relativePath, cells)
  }

  override def listNotebooks(): RIO[BaseEnv with GlobalEnv, List[String]] = {
    for {
      rootNBs <- root.listNotebooks()
      mountNbs <- repos.map {
        case (base, repo) => repo.listNotebooks().map(_.map(nbPath => Paths.get(base, nbPath).toString))
      }.toList.sequence
    } yield rootNBs ++ mountNbs.flatten
  }

  override def createNotebook(originalPath: String, maybeContent: Option[String]): RIO[BaseEnv with GlobalEnv, String] = delegate(originalPath) {
    (repo, relativePath, base) =>
        for {
          nbPath <- repo.createNotebook(relativePath, maybeContent)
        } yield base.map(b => Paths.get(b, nbPath).toString).getOrElse(nbPath)
  }

  override def renameNotebook(srcPath: String, destPath: String): RIO[BaseEnv with GlobalEnv, String] = {
    val originalPath = Paths.get(srcPath)
    val originalBase = originalPath.getName(0)

    val newPath = Paths.get(destPath)
    val newBase = newPath.getName(0)

    if (originalBase == newBase) {
      for {
        (renamed, base) <- delegate(originalPath.toString) {
          (repo, relativePath, base) =>
            repo.renameNotebook(relativePath, newBase.relativize(newPath).toString).map(_ -> base)
        }
      } yield base.map(b => Paths.get(b, renamed).toString).getOrElse(renamed)
    } else {
      for {
        (srcNb, srcBase) <- delegate(srcPath)((repo, relPath, base) => repo.loadNotebook(relPath).map(_ -> base))
        (destBase, dest)    <- delegate(destPath)((repo, relPath, base) => repo.saveNotebook(relPath, srcNb).map(_ => base -> relPath))
        _                <- delegate(srcPath)((repo, relPath, _) => repo.deleteNotebook(relPath))
      } yield destBase.map(base => Paths.get(base, dest).toString).getOrElse(dest)
    }
  }

  override def deleteNotebook(originalPath: String): RIO[BaseEnv with GlobalEnv, Unit] = delegate(originalPath) {
    (repo, relativePath, _) => repo.deleteNotebook(relativePath)
  }

  override def initStorage(): RIO[BaseEnv with GlobalEnv, Unit] = for {
    _ <- root.initStorage()
    _ <- repos.values.map(_.initStorage()).toList.sequence
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

  def notebookURI(path: String): RIO[BaseEnv with GlobalEnv, Option[URI]] = {
    val repoPath = this.path.resolve(path)
    notebookExists(path).map {
      exists =>
        if (exists) {
          Option(repoPath.toAbsolutePath.toUri)
        } else {
          None
        }
    }
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
            val dir = absNewPath.getParent.toFile
            if (!dir.exists()) {
              dir.mkdirs()
            }
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
