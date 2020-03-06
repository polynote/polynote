package polynote.server

import java.util.concurrent.atomic.AtomicInteger

import cats.{Applicative, MonadError}
import cats.effect.{Concurrent, Timer}
import cats.instances.list._
import cats.syntax.either._
import cats.syntax.traverse._
import fs2.Stream
import fs2.concurrent.{Queue, Topic}
import org.http4s.Response
import org.http4s.server.websocket.WebSocketBuilder
import org.http4s.websocket.WebSocketFrame
import org.http4s.websocket.WebSocketFrame.Binary
import polynote.buildinfo.BuildInfo
import polynote.kernel
import polynote.kernel.util.{Publish, RefMap}
import polynote.kernel.{BaseEnv, ClearResults, StreamThrowableOps, StreamingHandles, TaskG, UpdatedTasks}
import polynote.kernel.environment.{Env, PublishMessage}
import polynote.kernel.interpreter.Interpreter
import polynote.kernel.logging.Logging
import polynote.messages._
import polynote.server.auth.{Identity, IdentityProvider, UserIdentity}
import zio.{Has, Promise, RIO, Task, ZIO}

import scala.collection.immutable.SortedMap
import scala.concurrent.duration.{Duration, SECONDS}

class SocketSession(
  handler: SessionHandler,
  broadcastAll: Topic[Task, Option[Message]]
) {

  private def toFrame(message: Message): Task[Binary] = {
    Message.encode[Task](message).map(bits => Binary(bits.toByteVector))
  }

  lazy val toResponse: RIO[SessionEnv, Response[Task]] = for {
    input     <- Queue.unbounded[Task, WebSocketFrame]
    output    <- Queue.unbounded[Task, WebSocketFrame]
    processor <- process(input, output)
    handlerClosed = handler.awaitClosed.as(Either.right[Throwable, Unit](()))
    fiber     <- processor.interruptWhen(handlerClosed).compile.drain.ignore.forkDaemon
    keepalive <- Stream.awakeEvery[Task](Duration(10, SECONDS)).map(_ => WebSocketFrame.Ping())
      .interruptWhen(handlerClosed)
      .through(output.enqueue)
      .compile.drain.ignore.forkDaemon
    allOutputs = Stream.emits(Seq(output.dequeue, broadcastAll.subscribe(128).unNone.evalMap(toFrame))).parJoinUnbounded
    logging   <- ZIO.access[Logging](identity)
    response  <- WebSocketBuilder[Task].build(
      allOutputs.terminateAfter(_.isInstanceOf[WebSocketFrame.Close]) ++ Stream.eval(handler.close()).drain,
      input.enqueue,
      onClose = keepalive.interrupt *> fiber.interrupt.unit *> handler.close())
  } yield response

  private def process(
    input: Queue[Task, WebSocketFrame],
    output: Queue[Task, WebSocketFrame]
  ): ZIO[SessionEnv, Nothing, Stream[Task, Unit]] = ZIO.environment[SessionEnv].map {
    env =>
      val publishMessage = Publish(output).contraFlatMap(toFrame)
      Stream.eval(handshake.provide(env)).evalMap(publishMessage.publish1) ++ input.dequeue.flatMap {
        case WebSocketFrame.Close(_) => Stream.eval(output.enqueue1(WebSocketFrame.Close())).drain
        case WebSocketFrame.Binary(data, true) => Stream.eval(Message.decode[Task](data))
        case _ => Stream.empty
      }.evalMap {
        message =>
          handler.accept(message).catchAll {
            err =>
              Logging.error("Kernel error", err) *>
              PublishMessage(Error(0, err))
          }.provide(env ++ Has(publishMessage)).forkDaemon.unit
      }
  }

  private def handshake: RIO[SessionEnv, ServerHandshake] =
    for {
      factories <- Interpreter.Factories.access
      identity  <- UserIdentity.access
    } yield ServerHandshake(
      (SortedMap.empty[String, String] ++ factories.mapValues(_.head.languageName)).asInstanceOf[TinyMap[TinyString, TinyString]],
      serverVersion = BuildInfo.version,
      serverCommit = BuildInfo.commit,
      identity = identity.map(i => Identity(i.name, i.avatar.map(ShortString)))
    )
}

class SessionHandler(
  notebookManager: NotebookManager.Service,
  closed: Promise[Throwable, Unit]
) {

  import auth.Permission
  import IdentityProvider.checkPermission

  def accept(message: Message): RIO[SessionEnv with PublishMessage, Unit] =
    handler.applyOrElse(message, unhandled)

  def awaitClosed: ZIO[Any, Nothing, Either[Throwable, Unit]] = closed.await.either
  def close(): Task[Unit] = closed.succeed(()).unit

  private def unhandled(msg: Message): RIO[BaseEnv, Unit] = Logging.warn(s"Unhandled message type ${msg.getClass.getName}")

  private val handler: PartialFunction[Message, RIO[SessionEnv with PublishMessage, Unit]] = {
    case ListNotebooks(_) =>
      notebookManager.list().flatMap {
        notebooks => PublishMessage(ListNotebooks(notebooks.map(ShortString.apply)))
      }

    case CreateNotebook(path, maybeContent) =>
      checkPermission(Permission.CreateNotebook(path)) *> notebookManager.create(path, maybeContent).unit

    case RenameNotebook(path, newPath) =>
      checkPermission(Permission.CreateNotebook(newPath)) *>
        checkPermission(Permission.DeleteNotebook(path)) *>
        notebookManager.rename(path, newPath).unit

    case CopyNotebook(path, newPath) =>
      checkPermission(Permission.CreateNotebook(newPath)) *>
        notebookManager.copy(path, newPath).unit

    case DeleteNotebook(path) =>
      checkPermission(Permission.DeleteNotebook(path)) *> notebookManager.delete(path)

    case RunningKernels(_) => for {
      paths          <- notebookManager.listRunning()
      statuses       <- paths.map(notebookManager.status).sequence
      kernelStatuses  = paths.zip(statuses).map { case (p, s) => ShortString(p) -> s }
      _              <- PublishMessage(RunningKernels(kernelStatuses))
    } yield ()

    case other =>
      ZIO.unit
  }
}

object SocketSession {
  def apply(broadcastAll: Topic[Task, Option[Message]]): RIO[BaseEnv with NotebookManager, SocketSession] =
    for {
      notebookManager  <- NotebookManager.access
      closed           <- Promise.make[Throwable, Unit]
      handler           = new SessionHandler(notebookManager, closed)
    } yield new SocketSession(handler, broadcastAll)

  private[server] val sessionId = new AtomicInteger(0)
}
