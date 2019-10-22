package polynote.testing.repository

import java.io.FileNotFoundException

import polynote.kernel.TaskB
import polynote.messages._
import polynote.server.repository.NotebookRepository
import zio.{Task, UIO, ZIO}

import scala.collection.mutable

class MemoryRepository extends NotebookRepository[TaskB] {
  private val notebooks = new mutable.HashMap[String, Notebook]()

  def notebookExists(path: String): UIO[Boolean] = ZIO.effectTotal(notebooks contains path)

  def loadNotebook(path: String): Task[Notebook] = ZIO.effectTotal(notebooks.get(path)).get.mapError(err => new FileNotFoundException(path))

  def saveNotebook(path: String, cells: Notebook): UIO[Unit] = ZIO.effectTotal(notebooks.put(path, cells))

  def listNotebooks(): UIO[List[String]] = ZIO.effectTotal(notebooks.keys.toList)

  def createNotebook(path: String, maybeUriOrContent: Option[Either[String, String]]): UIO[String] =
    ZIO.effectTotal(notebooks.put(path, Notebook(path, ShortList.of(), None))).as(path)

  def initStorage(): TaskB[Unit] = ZIO.unit
}
