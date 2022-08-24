package polynote.server

import cats.instances.list._
import cats.syntax.traverse._
import polynote.buildinfo.BuildInfo
import polynote.kernel.util.Publish
import polynote.kernel.BaseEnv
import polynote.kernel.environment.{Config, Env, PublishMessage}
import polynote.kernel.interpreter.Interpreter
import polynote.kernel.logging.Logging
import polynote.messages._
import polynote.server.auth.IdentityProvider.checkPermission
import polynote.server.auth.{IdentityProvider, Permission, UserIdentity}
import polynote.config.HotkeyInfo
import uzhttp.websocket.Frame
import zio.stream.{Stream, Take, UStream, ZStream}
import zio.Queue
import zio.{Promise, RIO, Task, URIO, ZIO}

import scala.collection.immutable.SortedMap

object SocketSession {

  def apply(in: Stream[Throwable, Frame], broadcastMessages: UStream[Message]): URIO[SessionEnv with NotebookManager, Stream[Throwable, Frame]] =
    for {
      output          <- Queue.unbounded[Take[Nothing, Message]]
      publishMessage  <- Env.add[SessionEnv with NotebookManager](Publish(output))
      env             <- ZIO.environment[SessionEnv with NotebookManager with PublishMessage]
      closed          <- Promise.make[Throwable, Unit]
      _               <- broadcastMessages.interruptWhen(closed.await.run).foreach(publishMessage.publish).forkDaemon
      close            = closeQueueIf(closed, output)
    } yield parallelStreams(
        toFrames(ZStream.fromEffect(handshake) ++ Stream.fromQueue(output).flattenTake),
        in.handleMessages(close)(handler andThen errorHandler) ++ closeStream(closed, output),
        keepaliveStream(closed)).provide(env).catchAllCause {
      cause =>
        ZStream.empty
    }

  private val handler: Message => RIO[SessionEnv with PublishMessage with NotebookManager, Option[Message]] = {
    case ListNotebooks(_) =>
      NotebookManager.list().map {
        notebooks => Some(ListNotebooks(notebooks.map(ShortString.apply)))
      }

    case CreateNotebook(path, maybeContent) =>
      NotebookManager.assertValidPath(path) *>
        checkPermission(Permission.CreateNotebook(path)) *> NotebookManager.create(path, maybeContent).as(None)

    case RenameNotebook(path, newPath) =>
      (NotebookManager.assertValidPath(path) &> NotebookManager.assertValidPath(newPath)) *>
        checkPermission(Permission.CreateNotebook(newPath)) *> checkPermission(Permission.DeleteNotebook(path)) *>
        NotebookManager.rename(path, newPath).as(None)

    case CopyNotebook(path, newPath) =>
      (NotebookManager.assertValidPath(path) &> NotebookManager.assertValidPath(newPath)) *>
        checkPermission(Permission.CreateNotebook(newPath)) *>
        NotebookManager.copy(path, newPath).as(None)

    case DeleteNotebook(path) =>
      NotebookManager.assertValidPath(path) *>
        checkPermission(Permission.DeleteNotebook(path)) *> NotebookManager.delete(path).as(None)

    case RunningKernels(_) => getRunningKernels.asSome

    case KeepAlive(payload) =>
      // echo received KeepAlive message back to client.
      ZIO.succeed(Option(KeepAlive(payload)))

    case other =>
      ZIO.succeed(None)
  }

  val errorHandler: RIO[SessionEnv with PublishMessage with NotebookManager, Option[Message]] => RIO[SessionEnv with PublishMessage with NotebookManager, Option[Message]] =
    _.catchAll {
      err => Logging.error(err).as(Some(Error(0, err)))
    }

  def handshake: RIO[SessionEnv, ServerHandshake] =
    for {
      factories <- Interpreter.Factories.access
      identity  <- UserIdentity.access
      config    <- Config.access
    } yield ServerHandshake(
      (SortedMap.empty[String, String] ++ factories.mapValues(_.head.languageName)).asInstanceOf[TinyMap[TinyString, TinyString]],
      serverVersion = BuildInfo.version,
      serverCommit = BuildInfo.commit,
      identity = identity.map(i => Identity(i.name, i.avatar.map(ShortString))),
      sparkTemplates = config.spark.flatMap(_.propertySets).getOrElse(Nil),
      customKeybindings = config.ui.customKeybindings.asInstanceOf[TinyMap[TinyString, HotkeyInfo]]
    )

  def getRunningKernels: RIO[SessionEnv with PublishMessage with NotebookManager, RunningKernels] = for {
    paths          <- NotebookManager.listRunning()
    statuses       <- ZIO.collectAllPar(paths.map(NotebookManager.status))
    kernelStatuses  = paths.zip(statuses).map { case (p, s) => ShortString(p) -> s }
  } yield RunningKernels(kernelStatuses)
}
