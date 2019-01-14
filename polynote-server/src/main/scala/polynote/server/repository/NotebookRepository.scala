package polynote.server.repository

import java.nio.charset.StandardCharsets
import java.nio.file.{FileAlreadyExistsException, FileVisitOption, Files, Path}

import scala.collection.JavaConverters._
import cats.effect.{ContextShift, IO}
import polynote.messages.{Notebook, NotebookCell, ShortList, ShortString}

import scala.concurrent.ExecutionContext

trait NotebookRepository[F[_]] {

  def notebookExists(path: String): F[Boolean]

  def loadNotebook(path: String): F[Notebook]

  def saveNotebook(path: String, cells: Notebook): F[Unit]

  def listNotebooks(): F[List[String]]

  def createNotebook(path: String): F[String]

}

trait FileBasedRepository extends NotebookRepository[IO] {
  def path: Path
  def chunkSize: Int
  def executionContext: ExecutionContext

  protected def pathOf(notebook: String): Path = path.resolve(notebook)

  protected def loadString(path: String)(implicit contextShift: ContextShift[IO]): IO[String] = for {
    content <- readBytes(java.nio.file.Files.newInputStream(pathOf(path)), chunkSize, executionContext)
  } yield new String(content.toArray, StandardCharsets.UTF_8)

  def writeString(path: String, content: String): IO[Unit] = IO {
    java.nio.file.Files.write(pathOf(path), content.getBytes(StandardCharsets.UTF_8))
  }.map(_ => ())

  protected def defaultExtension: String

  protected def validNotebook(file: Path): Boolean = file.toString.endsWith(s".$defaultExtension")
  protected def maxDepth: Int = 4

  def listNotebooks(): IO[List[String]] =
    IO(Files.walk(path, maxDepth, FileVisitOption.FOLLOW_LINKS).iterator().asScala.drop(1).filter(validNotebook).toList).map {
      paths => paths.map {
        path => this.path.relativize(path).toString
      }
    }

  def notebookExists(path: String): IO[Boolean] = {
    val repoPath = this.path.resolve(path)
    IO(repoPath.toFile.exists())
  }

  def createNotebook(path: String): IO[String] = {
    val ext = s".$defaultExtension"
    val noExtPath = path.replaceFirst("""^/+""", "").stripSuffix(ext)
    val extPath = noExtPath + ext

    val defaultTitle = noExtPath.split('/').last.replaceAll("[\\s\\-_]+", " ").trim()
    val emptyNotebook = Notebook(
      ShortString(extPath),
      ShortList(
        NotebookCell("Cell0", "text", s"# $defaultTitle\n\nThis is a text cell. Start editing!") :: Nil
      ),
      None
    )

    notebookExists(extPath).flatMap {
      case true  => IO.raiseError(new FileAlreadyExistsException(extPath))
      case false => saveNotebook(extPath, emptyNotebook).map {
        _ => extPath
      }
    }
  }
}
