package polynote.server

import java.io.File
import java.util.concurrent.ConcurrentHashMap

import cats.Monad
import cats.effect.{ContextShift, Fiber, IO}
import cats.effect.concurrent.Semaphore
import polynote.server.repository.NotebookRepository

/**
  * This is how the sessions interact with notebooks. The sessions don't think about writing changes etc. They'll
  * open a [[SharedNotebook]] and from there get a [[NotebookRef]] in exchange for an output queue. Their interactions
  * with the notebook will go through the NotebookRef.
  */
abstract class NotebookManager[F[_]](implicit F: Monad[F]) {

  def getNotebook(path: String): F[SharedNotebook[F]]

  def listNotebooks(): F[List[String]]

  def createNotebook(path: String): F[String]

}

class IONotebookManager(
  repository: NotebookRepository[IO],
  kernelFactory: KernelFactory[IO])(implicit
  contextShift: ContextShift[IO]
) extends NotebookManager[IO] {

  private val writers = new ConcurrentHashMap[String, Fiber[IO, Unit]]
  private val notebooks = new ConcurrentHashMap[String, SharedNotebook[IO]]
  private val loadingNotebook = Semaphore[IO](1).unsafeRunSync()

  private def writeChanges(notebook: SharedNotebook[IO]): IO[Fiber[IO, Unit]] = notebook.versions.map(_._2).evalMap {
    updated => repository.saveNotebook(notebook.path, updated)
  }.compile.drain.start

  private def loadNotebook(path: String): IO[SharedNotebook[IO]] = loadingNotebook.acquire.bracket { _ =>
    Option(notebooks.get(path)) match {
      case Some(sharedNotebook) => IO.pure(sharedNotebook)
      case None =>
        for {
          loaded   <- repository.loadNotebook(path)
          notebook <- IOSharedNotebook(path, loaded, kernelFactory)
          _        <- IO { notebooks.put(path, notebook); () }
          writer   <- writeChanges(notebook)
          _        <- IO { writers.put(path, writer); () }
        } yield notebook
    }
  }(_ => loadingNotebook.release)

  def getNotebook(path: String): IO[SharedNotebook[IO]] = notebooks.synchronized {
    Option(notebooks.get(path)) match {
      case Some(sharedNotebook) => IO.pure(sharedNotebook)
      case None => loadNotebook(path)
    }
  }

  override def listNotebooks(): IO[List[String]] = repository.listNotebooks()

  override def createNotebook(path: String): IO[String] = repository.createNotebook(path)

}