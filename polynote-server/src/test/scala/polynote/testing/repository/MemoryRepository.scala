package polynote.testing.repository

import java.io.FileNotFoundException
import java.net.URI

import polynote.kernel.TaskB
import polynote.messages._
import polynote.server.repository.NotebookRepository
import zio.{Task, UIO, ZIO}

import scala.collection.mutable

class MemoryRepository extends NotebookRepository {
  private val notebooks = new mutable.HashMap[String, Notebook]()

  def notebookExists(path: String): UIO[Boolean] = ZIO.effectTotal(notebooks contains path)

  def notebookURI(path: String): UIO[Option[URI]] = ZIO.effectTotal(if (notebooks contains path) Option(new URI(s"memory://$path")) else None)

  def loadNotebook(path: String): Task[Notebook] = ZIO.effectTotal(notebooks.get(path)).get.mapError(err => new FileNotFoundException(path))

  def saveNotebook(nb: Notebook): UIO[Unit] = ZIO.effectTotal(notebooks.put(nb.path, nb))

  def listNotebooks(): UIO[List[String]] = ZIO.effectTotal(notebooks.keys.toList)

  def createNotebook(path: String, maybeUriOrContent: Option[String]): UIO[String] =
    ZIO.effectTotal(notebooks.put(path, Notebook(path, ShortList.of(), None))).as(path)

  def initStorage(): TaskB[Unit] = ZIO.unit

  def renameNotebook(path: String, newPath: String): TaskB[String] = ZIO.effectTotal(notebooks.get(path)).get.mapError(_ => new FileNotFoundException(path)).flatMap {
    notebook => ZIO.effectTotal {
      notebooks.remove(path)
      notebooks.put(newPath, notebook)
      newPath
    }
  }

  def deleteNotebook(path: String): TaskB[Unit] = ZIO.effectTotal(notebooks.get(path)).flatMap {
    case None    => ZIO.fail(new FileNotFoundException(path))
    case Some(_) => ZIO.effectTotal(notebooks.remove(path)).unit
  }
}
