package polynote.server

import cats.instances.list._
import cats.syntax.traverse._
import fs2.concurrent.Topic
import polynote.buildinfo.BuildInfo
import polynote.kernel.util.Publish
import polynote.kernel.{BaseEnv, StreamThrowableOps}
import polynote.kernel.environment.{Env, PublishMessage}
import polynote.kernel.interpreter.Interpreter
import polynote.kernel.logging.Logging
import polynote.messages._
import polynote.server.auth.IdentityProvider.checkPermission
import polynote.server.auth.{IdentityProvider, Permission, UserIdentity}
import uzhttp.websocket.Frame
import zio.stream.ZStream
import zio.stream.{Stream, Take}
import zio.Queue
import zio.{Promise, RIO, Task, URIO, ZIO}

import scala.collection.immutable.SortedMap

class SocketSession(
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

  def handshake: RIO[SessionEnv, ServerHandshake] =
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

object SocketSession {

  def apply(in: Stream[Throwable, Frame], broadcastAll: Topic[Task, Option[Message]]): URIO[SessionEnv with NotebookManager, Stream[Throwable, Frame]] =
    for {
      output          <- Queue.unbounded[Take[Nothing, Message]]
      publishMessage  <- Env.add[SessionEnv with NotebookManager](Publish(output): Publish[Task, Message])
      env             <- ZIO.environment[SessionEnv with NotebookManager with PublishMessage]
      closed          <- Promise.make[Throwable, Unit]
      _               <- broadcastAll.subscribe(32).unNone.interruptAndIgnoreWhen(closed).through(publishMessage.publish).compile.drain.forkDaemon
      close            = closeQueueIf(closed, output)
    } yield parallelStreams(
        toFrames(ZStream.fromEffect(handshake) ++ Stream.fromQueueWithShutdown(output).unTake),
        in.handleMessages(close)(handler andThen errorHandler).haltWhen(closed) ++ closeStream(closed, output),
        keepaliveStream(closed)).provide(env)

  private val handler: Message => RIO[SessionEnv with PublishMessage with NotebookManager, Option[Message]] = {
    case ListNotebooks(_) =>
      NotebookManager.list().map {
        notebooks => Some(ListNotebooks(notebooks.map(ShortString.apply)))
      }

    case CreateNotebook(path, maybeContent) =>
      checkPermission(Permission.CreateNotebook(path)) *> NotebookManager.create(path, maybeContent).as(None)

    case RenameNotebook(path, newPath) =>
      checkPermission(Permission.CreateNotebook(newPath)) *>
        checkPermission(Permission.DeleteNotebook(path)) *>
        NotebookManager.rename(path, newPath).as(None)

    case CopyNotebook(path, newPath) =>
      checkPermission(Permission.CreateNotebook(newPath)) *>
        NotebookManager.copy(path, newPath).as(None)

    case DeleteNotebook(path) =>
      checkPermission(Permission.DeleteNotebook(path)) *> NotebookManager.delete(path).as(None)

    case RunningKernels(_) => for {
      paths          <- NotebookManager.listRunning()
      statuses       <- ZIO.collectAllPar(paths.map(NotebookManager.status))
      kernelStatuses  = paths.zip(statuses).map { case (p, s) => ShortString(p) -> s }
    } yield Some(RunningKernels(kernelStatuses))

    case other =>
      ZIO.succeed(None)
  }

  val errorHandler: RIO[SessionEnv with PublishMessage with NotebookManager, Option[Message]] => RIO[SessionEnv with PublishMessage with NotebookManager, Option[Message]] =
    _.catchAll {
      err => Logging.error("Error processing message", err).as(Some(Error(0, err)))
    }

  def handshake: RIO[SessionEnv, ServerHandshake] =
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
