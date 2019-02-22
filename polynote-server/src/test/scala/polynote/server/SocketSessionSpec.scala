package polynote.server

import java.util.ServiceLoader
import java.util.concurrent.atomic.AtomicReference

import fs2.Stream
import cats.effect._
import cats.effect.concurrent.Ref
import cats.effect.internals.IOContextShift
import fs2.concurrent.{Enqueue, Queue, SignallingRef}
import org.scalatest.{FreeSpec, Matchers}
import polynote.kernel.{Kernel, KernelBusyState}
import polynote.kernel.dependency.CoursierFetcher
import polynote.kernel.lang.scal.ScalaInterpreter
import polynote.kernel.lang.{LanguageKernel, LanguageKernelService}
import polynote.messages._
import polynote.server.repository.NotebookRepository

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext

// TODO: these tests are more integration than unit tests. Ideally we'd be able to modularize a bunch of this stuff so
//       would be easier to write unit tests
class SocketSessionSpec extends FreeSpec with Matchers {

  "A SocketSession" - {
    "can be created" in {
      val notebook = Notebook(ShortString("test"), ShortList(List.empty), None)
      val nbManager = new IONotebookManager(new MockNotebookRepo(notebook), kernelFactory)
      implicit val timer: Timer[IO] = IO.timer(ExecutionContext.global)
      val ss = new SocketSession(nbManager)
      val response = sendMessage(ss, StartKernel(ShortString("test"), StartKernel.NoRestart)).flatMap(_.compile.toVector).unsafeRunSync()
      response.head shouldEqual KernelStatus(ShortString("test"), KernelBusyState(false, true))
    }
  }

  def sendMessage(ss: SocketSession, msg: Message): IO[Stream[IO, Message]] = {
    val dummyQueue = Queue.unbounded[IO, Message].unsafeRunSync()
    ss.respond(msg, dummyQueue)
  }

  val dependencyFetcher = new CoursierFetcher()

  val subKernels = ServiceLoader.load(classOf[LanguageKernelService]).iterator.asScala.toSeq
    .sortBy(_.priority)
    .foldLeft(Map.empty[String, LanguageKernel.Factory[IO]]) {
      (accum, next) => accum ++ next.languageKernels
    }

  implicit val ctxShift: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  val kernelFactory: KernelFactory[IO] = new IOKernelFactory(Map("scala" -> dependencyFetcher), subKernels)

  class MockNotebookRepo(initialNB: Notebook) extends NotebookRepository[IO] {

    val nbRef = Ref.of[IO, Notebook](initialNB).unsafeRunSync()

    def getNotebook(path: String): IO[SharedNotebook[IO]] = nbRef.get.flatMap(nb => IOSharedNotebook("test", nb, kernelFactory))

    override def listNotebooks(): IO[List[String]] = IO.pure(List("test"))

    override def createNotebook(path: String): IO[String] = IO.pure(path)

    def interpreterNames: Map[String, String] = Map("scala" -> "scala")

    override def notebookExists(path: String): IO[Boolean] = IO.pure(true)

    override def loadNotebook(path: String): IO[Notebook] = nbRef.get

    override def saveNotebook(path: String, cells: Notebook): IO[Unit] = IO.unit
  }

  class MockSharedNotebook(nb: Notebook, kernel: Kernel[IO]) extends SharedNotebook[IO] {
    override def path: String = "test"

    /**
      * Open a reference to this shared notebook.
      *
      * @param name A string identifying who is opening the notebook (i.e. their username or email)
      * @param oq   A queue to which messages to the subscriber (such as notifications of foreign updates) will be submitted
      * @return A [[NotebookRef]] which the caller can use to send updates to the shared notebook
      */
    override def open(name: String, oq: Enqueue[IO, Message]): IO[NotebookRef[IO]] =
      for {
        nbRef <- Ref.of[IO, Notebook](nb)
        kernelRef <- Ref.of[IO, Kernel[IO]](kernel)
      } yield {
        new MockNotebookRef(nbRef, kernelRef)
      }

    override def versions: Stream[IO, (Int, Notebook)] = throw new NotImplementedError()

  }

  class MockNotebookRef(nb: Ref[IO, Notebook], kernelRef: Ref[IO, Kernel[IO]]) extends NotebookRef[IO] {
    override def path: String = "test"

    override def get: IO[Notebook] = nb.get

    /**
      * Apply an update to the notebook
      *
      * @return The global version after the update was applied
      */
    override def update(update: NotebookUpdate): IO[Int] = nb.get.flatMap { notebook =>
      val doUpdate = update match {
        case InsertCell(_, _, _, cell, after) => nb.set(notebook.insertCell(cell, after))
        case DeleteCell(_, _, _, id)          => nb.set(notebook.deleteCell(id))
        case UpdateCell(_, _, _, id, edits)   => nb.set(notebook.editCell(id, edits))
        case UpdateConfig(_, _, _, config)    => nb.set(notebook.copy(config = Some(config)))
        case SetCellLanguage(_, _, _, id, lang) => nb.set(notebook.updateCell(id)(_.copy(language = lang)))
      }

      doUpdate.map(_ => 0)
    }

    /**
      * Close this reference to the shared notebook
      */
    override def close(): IO[Unit] = IO.unit

    override def isKernelStarted: IO[Boolean] = IO.pure(true)

    override def getKernel: IO[Kernel[IO]] = kernelRef.get

    override def shutdownKernel(): IO[Unit] = kernelRef.get.flatMap(_.shutdown())

    override def runCells(ids: List[String]): IO[Stream[IO, Message]] = getKernel.map { kernel =>
      Stream.emits(ids).flatMap { id =>
        Stream.eval(kernel.runCell(id)).flatten.map { result =>
          CellResult(ShortString(path), TinyString(id), result)
        }
      }
    }
  }

}
