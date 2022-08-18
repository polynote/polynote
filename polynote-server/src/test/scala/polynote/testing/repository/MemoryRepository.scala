package polynote.testing.repository

import java.io.FileNotFoundException
import java.net.URI

import polynote.kernel.{BaseEnv, GlobalEnv, NotebookRef, TaskB}
import polynote.messages._
import polynote.server.repository.NotebookRepository
import polynote.testing.kernel.MockNotebookRef
import zio.{RIO, Task, UIO, ZIO}

import scala.collection.mutable

class MemoryRepository extends NotebookRepository {
  private val notebooks = new mutable.HashMap[String, Notebook]()

  def notebookExists(path: String): UIO[Boolean] = ZIO.effectTotal(notebooks contains path)

  def notebookURI(path: String): UIO[Option[URI]] = ZIO.effectTotal(if (notebooks contains path) Option(new URI(s"memory://$path")) else None)

  def loadNotebook(path: String): Task[Notebook] = ZIO.effectTotal(notebooks.get(path)).get.mapError(err => new FileNotFoundException(path))

  def openNotebook(path: String): RIO[BaseEnv with GlobalEnv, NotebookRef] = loadNotebook(path).flatMap(nb => MockNotebookRef(nb, tup => saveNotebook(tup._2)))

  def saveNotebook(nb: Notebook): UIO[Unit] = ZIO.effectTotal(notebooks.put(nb.path, nb))

  def listNotebooks(): UIO[List[String]] = ZIO.effectTotal(notebooks.keys.toList)

  def createNotebook(path: String, maybeUriOrContent: Option[String]): UIO[String] =
    ZIO.effectTotal(notebooks.put(path, Notebook(path, ShortList.of(), None))).as(path)

  def createAndOpen(path: String, notebook: Notebook, version: Int): RIO[BaseEnv with GlobalEnv, NotebookRef] =
    ZIO.effectTotal(notebooks.put(path, notebook)).flatMap {
      _ => MockNotebookRef(notebook, tup => saveNotebook(tup._2), version)
    }

  def initStorage(): TaskB[Unit] = ZIO.unit

  def renameNotebook(path: String, newPath: String): Task[String] = loadNotebook(path).map {
    notebook =>
      notebooks.put(newPath, notebook)
      notebooks.remove(path)
      newPath
  }

  def copyNotebook(path: String, newPath: String): TaskB[String] = loadNotebook(path).map {
    notebook =>
      notebooks.put(newPath, notebook)
      newPath
  }

  def deleteNotebook(path: String): TaskB[Unit] = ZIO.effectTotal(notebooks.get(path)).flatMap {
    case None    => ZIO.fail(new FileNotFoundException(path))
    case Some(_) => ZIO.effectTotal(notebooks.remove(path)).unit
  }
}
