package polynote.server

import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiFunction

import cats.effect._
import cats.effect.concurrent.{MVar, Ref, Semaphore}
import cats.syntax.all._
import cats.instances.list._
import cats.~>
import fs2.Stream
import fs2.concurrent.{Enqueue, Queue, SignallingRef, Topic}
import org.http4s.Response
import org.http4s.server.websocket.WebSocketBuilder
import org.http4s.websocket.WebSocketFrame
import WebSocketFrame._
import cats.data.OptionT
import org.log4s.getLogger
import polynote.kernel._
import polynote.kernel.util.{OptionEither, ReadySignal, WindowBuffer}
import polynote.messages._
import polynote.server.repository.NotebookRepository

import scala.concurrent.duration._
import scala.collection.JavaConverters._

class SocketSession(
  notebookManager: NotebookManager[IO],
  oq: Queue[IO, Message])(implicit
  contextShift: ContextShift[IO],
  timer: Timer[IO]
) {

  private val name: String = "Anonymous"  // TODO
  private[this] val logger = getLogger
  private val loadingNotebook = Semaphore[IO](1).unsafeRunSync()
  private val notebooks = new ConcurrentHashMap[String, NotebookRef[IO]]()

  private def toFrame(message: Message) = {
    Message.encode[IO](message).map(bits => Binary(bits.toByteVector))
  }

  private def logMessage(message: Message): IO[Unit]= IO(logger.info(message.toString))
  private def logErrorMessage(message: Message): IO[Unit]= message match {
    case Error(_, err) => logError(err)
    case _ => IO.unit
  }
  private def logError(error: Throwable): IO[Unit] = IO(logger.error(error)(error.getMessage))

  private val closeSignal = ReadySignal()

  private def shutdown(): IO[Unit] = {

    def closeNotebooks = notebooks.values.asScala.toList.map(_.close()).parSequence.as(())

    for {
      _ <- closeNotebooks
      _ <- closeSignal.complete
    } yield ()
  }

  lazy val toResponse: IO[Response[IO]] = Queue.unbounded[IO, WebSocketFrame].flatMap {
    iq =>
      /**
        * The evaluation semantics here are tricky. respond() is going to return a suspension which contains a
        * Stream with its own suspensions. We want to allow both the outer suspensions and the inner suspensions
        * to run in parallel, but we also want to make sure the outer suspensions are run in the order they were
        * received. So we start them as soon as they come in.
        *
        * TODO: can this be uncomplicated by having respond() just write to outbound queue rather than returning
        *       its own stream?
        */
      val responses = iq.dequeue.evalMap {

        case Binary(bytes, true) => Message.decode[IO](bytes).flatMap {
          message =>
            val resp = respond(message)
              // catch IO errors here so we don't terminate the stream later on
                .handleErrorWith { err =>
                  val re = ErrorResult(err)
                  IO(Stream.eval(logError(re).map(_ => Error(0, re))))
                }

            resp.start
        }

        case Close(data) => IO.pure(Stream.eval(shutdown()).drain).start

        case other => IO.pure(Stream.emit(badMsgErr(other))).start

      }.evalMap {
        fiber => fiber.join.uncancelable
      }.interruptWhen(closeSignal())

      val toClient: Stream[IO, WebSocketFrame] =
        Stream.awakeEvery[IO](10.seconds).map {
          d => Text("Ping!")  // Websocket is supposed to have its own keepalives, but this seems to prevent the connection from dying all the time while idle.
        }.merge {
          Stream(oq.dequeue.interruptWhen(closeSignal()), responses.parJoinUnbounded).parJoinUnbounded
            .handleErrorWith {
              err =>
                val re = UnrecoverableError(err)
                Stream.eval(logError(re).map(_ => Error(0, re)))
            }
            //.evalTap(logMessage)
            .evalTap(logErrorMessage)
            .evalMap(toFrame).handleErrorWith {
            err =>
              Stream.eval(logError(UnrecoverableError(err))).drain
          }
        }.interruptWhen(closeSignal())

      WebSocketBuilder[IO].build(toClient, iq.enqueue, onClose = IO.delay(shutdown()).flatten) <* oq.enqueue1(handshake)
  }

  private def getNotebook(path: String) = loadingNotebook.acquire.bracket { _ =>
    Option(notebooks.get(path)).map(IO.pure).getOrElse {
      notebookManager.getNotebook(path).flatMap {
        sharedNotebook => for {
          notebookRef <- sharedNotebook.open(name)
          _            = notebooks.put(path, notebookRef)
          _           <- notebookRef.messages.interruptWhen(closeSignal()).through(oq.enqueue).compile.drain.start
        } yield notebookRef
      }
    }
  }(_ => loadingNotebook.release)

  private def handshake: ServerHandshake =
    ServerHandshake(notebookManager.interpreterNames.asInstanceOf[TinyMap[TinyString, TinyString]])

  def respond(message: Message): IO[Stream[IO, Message]] = message match {
    case ListNotebooks(_) =>
      notebookManager.listNotebooks().map {
        notebooks => Stream.emit(ListNotebooks(notebooks.asInstanceOf[List[ShortString]]))
      }

    case LoadNotebook(path) =>
      getNotebook(path).map {
        notebookRef =>
          Stream.eval(notebookRef.get) ++
            Stream.eval(notebookRef.currentSymbols()).flatMap(results => Stream.emits(results).map(rv => CellResult(path, rv.sourceCell, rv))) ++
            Stream.eval(notebookRef.currentTasks()).map(tasks => KernelStatus(path, UpdatedTasks(tasks))) ++
            Stream.eval(notebookRef.currentStatus).map(KernelStatus(path, _)) ++
            Stream.eval(notebookRef.info).map(info => info.map(KernelStatus(path, _))).unNone
      }

    case CreateNotebook(path, maybeUriOrContent) =>
      notebookManager.createNotebook(path, maybeUriOrContent).map {
        actualPath => CreateNotebook(ShortString(actualPath), OptionEither.Neither)
      }.attempt.map {
        // TODO: is there somewhere more universal we can put this mapping?
        case Left(throwable) => Error(0, throwable)
        case Right(m) => m
      }.map(Stream.emit)

    case upConfig @ UpdateConfig(path, _, _, config) =>
      getNotebook(path).flatMap {
        notebookRef => notebookRef.update(upConfig).flatMap {
          globalVersion =>
            notebookRef.isKernelStarted.flatMap {
              case true => notebookRef.restartKernel()
              case false => notebookRef.startKernel()
            }
        } *> notebookRef.currentStatus.map(status => Stream.emit(KernelStatus(path, status)))
      }


    case NotebookUpdate(update) =>
      getNotebook(update.notebook).flatMap {
        notebookRef => notebookRef.update(update).map(globalVersion => Stream.empty) // TODO: notify client of global version
      }

    case RunCell(path, ids) =>
      for {
        notebookRef <- getNotebook(path)
        notebook <- notebookRef.get
        cells = ids.map(notebook.cell)
        results <- notebookRef.runCells(cells)
      } yield results.drain   // we throw away the messages because they already come through the notebook's messages stream

      // TODO: do we need to emit a kernel status here any more?

    case req@CompletionsAt(notebook, id, pos, _) =>
      for {
        notebookRef <- getNotebook(notebook)
        notebook    <- notebookRef.get
        cell         = notebook.cell(id)
        completions <- notebookRef.completionsAt(cell, pos).handleErrorWith {
          case RuntimeError(err) => IO.raiseError(err) // Since a completion request could start the Kernel, make sure to bubble these up
          case err =>
            IO(err.printStackTrace(System.err)).map(_ => Nil)
        }
      } yield Stream.emit(req.copy(completions = ShortList(completions)))

    case req@ParametersAt(notebook, id, pos, _) =>
      for {
        notebookRef <- getNotebook(notebook)
        notebook    <- notebookRef.get
        cell         = notebook.cell(id)
        parameters  <- notebookRef.parametersAt(cell, pos)
      } yield Stream.emit(req.copy(signatures = parameters))

    case KernelStatus(path, _) =>
      for {
        notebookRef <- getNotebook(path)
        status      <- notebookRef.currentStatus
      } yield Stream.emit(KernelStatus(path, status))

    case StartKernel(path, StartKernel.NoRestart) =>
      for {
        notebookRef <- getNotebook(path)
        _           <- notebookRef.startKernel()
        status      <- notebookRef.currentStatus
      } yield Stream.emit(KernelStatus(path, status))

    case StartKernel(path, StartKernel.WarmRestart) => ??? // TODO
    case StartKernel(path, StartKernel.ColdRestart) =>
      for {
        notebookRef <- getNotebook(path)
        _           <- notebookRef.restartKernel()
        status      <- notebookRef.currentStatus
      } yield Stream.emit(KernelStatus(path, status))

    case StartKernel(path, StartKernel.Kill) =>
      for {
        notebookRef <- getNotebook(path)
        _           <- notebookRef.shutdown()
        status      <- notebookRef.currentStatus
      } yield Stream.emit(KernelStatus(path, status))

    case HandleData(path, handleType, handle, count, _) =>
      for {
        notebookRef <- getNotebook(path)
        results     <- notebookRef.getHandleData(handleType, handle, count)
      } yield Stream.emit(HandleData(path, handleType, handle, count, results))

    case CancelTasks(path) =>
      for {
        notebookRef <- getNotebook(path)
        _           <- notebookRef.cancelTasks()
      } yield Stream.empty

    case ClearOutput(path) =>
      for {
        notebookRef   <- getNotebook(path)
        clearMessages <- notebookRef.clearOutput()
      } yield clearMessages

    case ms @ ModifyStream(path, fromHandle, ops, _) =>
      for {
        notebookRef <- getNotebook(path)
        result      <- notebookRef.modifyStream(fromHandle, ops)
      } yield Stream.emit(ms.copy(newRepr = result))

    case rh @ ReleaseHandle(path, handleType, handleId) =>
      for {
        notebookRef <- getNotebook(path)
        _           <- notebookRef.releaseHandle(handleType, handleId)
      } yield Stream.emit(rh)

    case other =>
      IO.pure(Stream.empty)
  }

  def badMsgErr(msg: WebSocketFrame) = Error(1, new RuntimeException(s"Received bad message frame ${truncate(msg.toString, 32)}"))

  def truncate(str: String, len: Int): String = if (str.length < len) str else str.substring(0, len - 4) + "..."

}

object SocketSession {

  def apply(
    notebookManager: NotebookManager[IO])(implicit
    contextShift: ContextShift[IO],
    timer: Timer[IO]
  ): IO[SocketSession] = Queue.unbounded[IO, Message].map {
    oq => new SocketSession(notebookManager, oq)
  }

}

case class UnrecoverableError(err: Throwable) extends Throwable(s"${err.getClass.getSimpleName}: ${err.getMessage}", err)
