package polynote.server

import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiFunction

import cats.effect._
import cats.effect.concurrent.{MVar, Ref, Semaphore}
import cats.syntax.all._
import cats.instances.list._
import cats.~>
import fs2.Stream
import fs2.concurrent.{Queue, SignallingRef, Topic}
import org.http4s.Response
import org.http4s.server.websocket.WebSocketBuilder
import org.http4s.websocket.WebsocketBits._
import org.log4s.getLogger
import polynote.kernel._
import polynote.kernel.util.WindowBuffer
import polynote.messages._
import polynote.server.repository.NotebookRepository

import scala.concurrent.duration._

class SocketSession(
  notebookManager: NotebookManager[IO],
  inBufferLength: Int = 100,
  outBufferLength: Int = 100)(implicit
  contextShift: ContextShift[IO],
  timer: Timer[IO]
) {

  private val name: String = "Anonymous"  // TODO

  private val inbound = Queue.bounded[IO, WebSocketFrame](inBufferLength)
  private val outbound = Queue.bounded[IO, Message](outBufferLength)
  private[this] val logger = getLogger

  private val loadingNotebook = Semaphore[IO](1).unsafeRunSync()
  private val notebooks = new ConcurrentHashMap[String, NotebookRef[IO]]()

  private def toFrame(message: Message) = {
    Message.encode[IO](message).map(bits => Binary(bits.toByteArray))
  }

  private def logMessage(message: Message): IO[Unit]= IO(logger.info(message.toString))
  private def logError(error: Throwable): IO[Unit] = IO(logger.error(error)(error.getMessage))

  lazy val toResponse: IO[Response[IO]] = (inbound, outbound).parMapN {
    (iq, oq) =>
      val fromClient = iq.enqueue

      // TODO: handle continuations/non-final?

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
          message => respond(message, oq).start
        }

        case other => IO.pure(Stream.emit(badMsgErr(other))).start
      }.evalMap {
        fiber => fiber.join.uncancelable
      }

      val toClient: Stream[IO, WebSocketFrame] =
        Stream.awakeEvery[IO](10.seconds).map {
          d => Text("Ping!")  // Websocket is supposed to have its own keepalives, but this seems to prevent the connection from dying all the time while idle.
        }.merge {
          Stream(oq.dequeue, responses.parJoinUnbounded).parJoinUnbounded
            .handleErrorWith {
              err =>
                val re = err match {
                  case RuntimeError(e) => e
                  case e => RuntimeError(e)
                }
                Stream.eval(logError(re).map(_ => Error(0, re)))
            }
            //.evalTap(logMessage)
            .evalMap(toFrame).handleErrorWith {
              err =>
                Stream.eval(logError(err)).drain
            }
        }

      WebSocketBuilder[IO].build(toClient, fromClient)
  }.flatten

  private def getNotebook(path: String, oq: Queue[IO, Message]) = loadingNotebook.acquire.bracket { _ =>
    Option(notebooks.get(path)).map(IO.pure).getOrElse {
      notebookManager.getNotebook(path).flatMap {
        sharedNotebook =>
          sharedNotebook.open(name, oq).flatMap {
            notebookRef => IO { notebooks.put(path, notebookRef); () }.as(notebookRef)
          }
      }
    }
  }(_ => loadingNotebook.release)


  def respond(message: Message, oq: Queue[IO, Message]): IO[Stream[IO, Message]] = message match {
    case ListNotebooks(_) =>
      notebookManager.listNotebooks().map {
        notebooks => Stream.emit(ListNotebooks(notebooks.asInstanceOf[List[ShortString]]))
      }

    case LoadNotebook(path) =>
      getNotebook(path, oq).map {
        notebookRef =>
          Stream.eval(notebookRef.get) ++ Stream.eval(notebookRef.currentStatus).map(KernelStatus(path, _))
      }

    case CreateNotebook(path) =>
      notebookManager.createNotebook(path).map {
        actualPath => Stream.emit(CreateNotebook(ShortString(actualPath)))
      }


    case upConfig @ UpdateConfig(path, _, _, config) =>
      getNotebook(path, oq).flatMap {
        notebookRef => notebookRef.update(upConfig).flatMap {
          globalVersion =>
            notebookRef.isKernelStarted.flatMap {
              case true => notebookRef.restartKernel()
              case false => IO.unit
            }
        }
      }.map(_ => Stream.empty)


    case NotebookUpdate(update) =>
      getNotebook(update.notebook, oq).flatMap {
        notebookRef => notebookRef.update(update).map(globalVersion => Stream.empty) // TODO: notify client of global version
      }

    case RunCell(path, ids) =>
      getNotebook(path, oq).flatMap {
        notebookRef => notebookRef.runCells(ids)
      }
      // TODO: do we need to emit a kernel status here any more?


    case req@CompletionsAt(notebook, id, pos, _) =>
      for {
        notebookRef <- getNotebook(notebook, oq)
        kernel      <- notebookRef.getKernel
        completions <- kernel.completionsAt(id, pos).handleErrorWith(err => IO(err.printStackTrace(System.err)).map(_ => Nil))
      } yield Stream.emit(req.copy(completions = ShortList(completions)))

    case req@ParametersAt(notebook, id, pos, _) =>
      for {
        notebookRef <- getNotebook(notebook, oq)
        kernel      <- notebookRef.getKernel
        parameters  <- kernel.parametersAt(id, pos)
      } yield Stream.emit(req.copy(signatures = parameters))

    case KernelStatus(path, _) =>
      for {
        notebookRef <- getNotebook(path, oq)
        status      <- notebookRef.currentStatus
      } yield Stream.emit(KernelStatus(path, status))

    case StartKernel(path, StartKernel.NoRestart) =>
      for {
        notebookRef <- getNotebook(path, oq)
        kernel      <- notebookRef.getKernel
        status      <- notebookRef.currentStatus
      } yield Stream.emit(KernelStatus(path, status))

    case StartKernel(path, StartKernel.WarmRestart) => ??? // TODO
    case StartKernel(path, StartKernel.ColdRestart) =>
      for {
        notebookRef <- getNotebook(path, oq)
        _           <- notebookRef.restartKernel()
        status      <- notebookRef.currentStatus
      } yield Stream.emit(KernelStatus(path, status))

    case StartKernel(path, StartKernel.Kill) =>
      for {
        notebookRef <- getNotebook(path, oq)
        _           <- notebookRef.shutdownKernel()
        status      <- notebookRef.currentStatus
      } yield Stream.emit(KernelStatus(path, status))


    case other =>
      IO.pure(Stream.empty)
  }

  def badMsgErr(msg: WebSocketFrame) = Error(1, new RuntimeException(s"Received bad message frame ${truncate(msg.toString, 32)}"))

  def truncate(str: String, len: Int): String = if (str.length < len) str else str.substring(0, len - 4) + "..."

}
