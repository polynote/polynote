package polynote.server

import java.io.File
import java.util.concurrent.ConcurrentHashMap

import org.log4s.{Logger, getLogger}
import cats.Monad
import cats.effect.{ContextShift, Fiber, IO}
import cats.effect.concurrent.Semaphore
import polynote.config.PolynoteConfig
import polynote.kernel.lang.LanguageInterpreter
import polynote.kernel.util.Nand
import polynote.server.repository.NotebookRepository

import scala.collection.immutable.SortedMap

/**
  * This is how the sessions interact with notebooks. The sessions don't think about writing changes etc. They'll
  * open a [[SharedNotebook]] and from there get a [[NotebookRef]] in exchange for an output queue. Their interactions
  * with the notebook will go through the NotebookRef.
  */
abstract class NotebookManager[F[_]](implicit F: Monad[F]) {

  def getNotebook(path: String): F[SharedNotebook[F]]

  def listNotebooks(): F[List[String]]

  def createNotebook(path: String, maybeUriOrContent: Nand[String, String]): F[String]

  def interpreterNames: Map[String, String]

}

class IONotebookManager(
  config: PolynoteConfig,
  repository: NotebookRepository[IO],
  kernelFactory: KernelFactory[IO])(implicit
  contextShift: ContextShift[IO]
) extends NotebookManager[IO] {

  private val writers = new ConcurrentHashMap[String, Fiber[IO, Unit]]
  private val notebooks = new ConcurrentHashMap[String, SharedNotebook[IO]]
  private val loadingNotebook = Semaphore[IO](1).unsafeRunSync()

  protected val logger: Logger = getLogger

  private def writeChanges(notebook: SharedNotebook[IO]): IO[Fiber[IO, Unit]] = notebook.versions.map(_._2).evalMap {
    updated => repository.saveNotebook(notebook.path, updated)
  }.handleErrorWith { err =>
    // TODO: Can we recover from this error? Or at least bubble it up to the UI?
    fs2.Stream.eval(IO(logger.error(err)("Error while writing notebook")))
  }.compile.drain.start

  private def loadNotebook(path: String): IO[SharedNotebook[IO]] = loadingNotebook.acquire.bracket { _ =>
    Option(notebooks.get(path)) match {
      case Some(sharedNotebook) => IO.pure(sharedNotebook)
      case None =>
        for {
          loaded   <- repository.loadNotebook(path)
          notebook <- IOSharedNotebook(path, loaded, kernelFactory, config)
          _        <- IO { notebooks.put(path, notebook); () }
          writer   <- writeChanges(notebook)
          _        <- IO { writers.put(path, writer); () }
        } yield notebook
    }
  }(_ => loadingNotebook.release)

  override lazy val interpreterNames: Map[String, String] = SortedMap.empty[String, String] ++
    LanguageInterpreter.factories.mapValues(_.languageName)

  def getNotebook(path: String): IO[SharedNotebook[IO]] = notebooks.synchronized {
    Option(notebooks.get(path)) match {
      case Some(sharedNotebook) => IO.pure(sharedNotebook)
      case None => loadNotebook(path)
    }
  }

  override def listNotebooks(): IO[List[String]] = repository.listNotebooks()

  override def createNotebook(path: String, maybeUriOrContent: Nand[String, String]): IO[String] =
    repository.createNotebook(path, maybeUriOrContent)
}