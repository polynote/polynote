package polynote.server.repository

import java.nio.charset.StandardCharsets
import java.nio.file.{FileVisitOption, Files, Path}

import cats.effect.{ConcurrentEffect, ContextShift, IO, Sync}
import cats.syntax.flatMap._
import cats.syntax.functor._
import io.circe.Printer
import org.http4s.client.blaze._
import polynote.config.PolynoteConfig
import polynote.messages._
import polynote.server.repository.ipynb.ZeppelinNotebook

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext

trait NotebookRepository[F[_]] {

  /**
    * @return Whether a notebook exists at the specified path
    */
  def notebookExists(path: String): F[Boolean]

  /**
    * @return The notebook at the specified path
    */
  def loadNotebook(path: String): F[Notebook]

  /**
    * Save the given notebook to the specified path
    */
  def saveNotebook(path: String, cells: Notebook): F[Unit]

  /**
    * @return A list of notebook paths that exist in this repository
    */
  def listNotebooks(): F[List[String]]

  // TODO: imports shouldn't happen on the server anymore... second arg should be Option[Notebook]
  /**
    * Create a notebook at the given path, optionally importing from the given URI (on the left) or the given content
    * string (on the right).
    */
  def createNotebook(path: String, maybeUriOrContent: Option[Either[String, String]]): F[String]

  /**
    * Initialize the storage for this repository (i.e. create directory if it doesn't exist)
    */
  def initStorage(): F[Unit]
}

abstract class FileBasedRepository[F[_]](implicit F: ConcurrentEffect[F], contextShift: ContextShift[F]) extends NotebookRepository[F] {
  def path: Path
  def chunkSize: Int
  def executionContext: ExecutionContext
  def config: PolynoteConfig

  protected def pathOf(relativePath: String): Path = path.resolve(relativePath)

  protected def loadString(path: String): F[String] = for {
    content <- readBytes(Files.newInputStream(pathOf(path)), chunkSize, executionContext)
  } yield new String(content.toArray, StandardCharsets.UTF_8)

  def writeString(relativePath: String, content: String): F[Unit] = F.delay {
    val nbPath = pathOf(relativePath)

    if (nbPath.getParent != this.path) {
      Files.createDirectories(nbPath.getParent)
    }

    Files.write(pathOf(relativePath), content.getBytes(StandardCharsets.UTF_8))
  }.map(_ => ())

  protected def defaultExtension: String

  protected def validNotebook(path: Path): Boolean = path.toString.endsWith(s".$defaultExtension")
  protected def maxDepth: Int = 4

  def listNotebooks(): F[List[String]] =
    F.delay(Files.walk(path, maxDepth, FileVisitOption.FOLLOW_LINKS).iterator().asScala.drop(1).filter(validNotebook).toList).map {
      paths => paths.map {
        path => this.path.relativize(path).toString
      }
    }

  def notebookExists(path: String): F[Boolean] = {
    val repoPath = this.path.resolve(path)
    F.delay(repoPath.toFile.exists())
  }

  val EndsWithNum = """^(.*)(\d+)$""".r

  def findUniqueName(path: String, ext: String): F[String] = {
    notebookExists(path + ext).flatMap {
      case true =>
        path match {
          case EndsWithNum(base, num) =>
            findUniqueName(s"$base${num.toInt + 1}", ext)
          case _ =>
            findUniqueName(s"${path}2", ext) // start at two because the first one is implicitly #1? Or is that weird?
        }
      case false =>
        F.pure(path)
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

  def createNotebook(relativePath: String, maybeUriOrContent: Option[Either[String, String]] = None): F[String] = {
    val ext = s".$defaultExtension"
    val noExtPath = relativePath.replaceFirst("""^/+""", "").stripSuffix(ext)

    if (relativeDepth(relativePath) > maxDepth) {
      F.raiseError(new IllegalArgumentException(s"Input path ($relativePath) too deep, maxDepth is $maxDepth"))
    } else {
      findUniqueName(noExtPath, ext).flatMap { name =>
        val extPath = name + ext
        val createOrImport = maybeUriOrContent match {
          case None =>
            val defaultTitle = name.split('/').last.replaceAll("[\\s\\-_]+", " ").trim()
            saveNotebook(extPath, emptyNotebook(extPath, defaultTitle))
          case Some(Left(uri)) =>
            BlazeClientBuilder[F](executionContext).resource.use { client =>
              client.expect[String](uri)
            }.flatMap { content =>
              writeString(extPath, content)
            }
          case Some(Right(content)) =>
            if (relativePath.endsWith(".json")) { // assume zeppelin
              import io.circe.parser.parse
              import io.circe.syntax._
              for {
                parsed <- F.fromEither(parse(content))
                  zep <- F.fromEither(parsed.as[ZeppelinNotebook])
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

  def initStorage(): F[Unit] = F.delay {
    if (!Files.exists(path)) {
      Files.createDirectories(path)
    }
  }
}
