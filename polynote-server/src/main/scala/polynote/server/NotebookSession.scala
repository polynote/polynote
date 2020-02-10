package polynote.server

import cats.{Applicative, MonadError}
import cats.effect.Concurrent
import cats.instances.list._
import cats.syntax.either._
import cats.syntax.traverse._
import fs2.Stream
import fs2.concurrent.Queue
import org.http4s.Response
import org.http4s.server.websocket.WebSocketBuilder
import org.http4s.websocket.WebSocketFrame
import polynote.kernel.{BaseEnv, ClearResults, GlobalEnv, PresenceUpdate, StreamThrowableOps, StreamingHandles, UpdatedTasks}
import polynote.kernel.environment.{Env, PublishMessage}
import polynote.kernel.logging.Logging
import polynote.kernel.util.Publish
import polynote.messages._
import polynote.server.SocketSession.sessionId
import polynote.server.auth.{Permission, UserIdentity}
import zio.{Promise, RIO, Task, UIO, URIO, ZIO}
import zio.syntax._

import scala.concurrent.duration.{Duration, SECONDS}


class NotebookSession(
  subscriber: KernelSubscriber,
  streamingHandles: StreamingHandles with BaseEnv,
  closed: Promise[Throwable, Unit],
  input: Queue[Task, WebSocketFrame],
  output: Queue[Task, WebSocketFrame],
  publishMessage: PublishMessage
) {

  def close(): URIO[SessionEnv, Unit] =
        subscriber.close() *> closed.succeed(()).flatMap {
          case true => (UserIdentity.access, subscriber.currentPath.orDie).map2 {
              (user, path) => output.enqueue1(WebSocketFrame.Close()).orDie *> Logging.info(s"Closing notebook session $path for $user")
            }.flatten
          case false => ZIO.unit
        }

  lazy val toResponse: ZIO[SessionEnv, Throwable, Response[Task]] = {
    for {
      env       <- ZIO.environment[SessionEnv]
      _         <- sendNotebookInfo()
      processor <- process(input, output)
      fiber     <- processor.interruptWhen(closed.await.either.as(Either.right[Throwable, Unit](()))).compile.drain.ignore.fork
      keepalive <- Stream.awakeEvery[Task](Duration(10, SECONDS)).map(_ => WebSocketFrame.Ping())
        .interruptWhen(closed.await.either.as(Either.right[Throwable, Unit](())))
        .through(output.enqueue)
        .compile.drain.ignore.fork
      response  <- WebSocketBuilder[Task].build(
        output.dequeue.terminateAfter(_.isInstanceOf[WebSocketFrame.Close]) ++ Stream.eval(close().provide(env)).drain,
        input.enqueue,
        onClose = keepalive.interrupt *> fiber.interrupt.unit *> close().provide(env))
    } yield response
  }.provideSomeM(Env.enrich[SessionEnv](publishMessage))

  def process(input: Queue[Task, WebSocketFrame], output: Queue[Task, WebSocketFrame]): URIO[SessionEnv, Stream[Task, Unit]] = {
    Env.enrich[SessionEnv](publishMessage).map {
      env => input.dequeue.flatMap {
        case WebSocketFrame.Close(_) => Stream.eval(output.enqueue1(WebSocketFrame.Close())).drain
        case WebSocketFrame.Binary(data, true) => Stream.eval(Message.decode[Task](data))
        case _ => Stream.empty
      }.evalMap {
        message =>
          handleMessage(message).supervised.catchAll {
            err => Logging.error("Kernel error", err) *> PublishMessage(Error(0, err))
          }.provide(env).fork.unit
      }
    }
  }

  private def sendNotebookInfo() = {
    def publishRunningKernelState(publisher: KernelPublisher) = for {
      kernel <- publisher.kernel
        _      <- kernel.values().flatMap(_.filter(_.sourceCell < 0).map(rv => PublishMessage(CellResult(rv.sourceCell, rv))).sequence)
        _      <- kernel.info().map(KernelStatus(_)) >>= PublishMessage.apply
    } yield ()

    for {
      notebook <- subscriber.notebook
      _        <- PublishMessage(notebook)
      status   <- subscriber.publisher.kernelStatus()
      _        <- PublishMessage(KernelStatus(status))
      _        <- if (status.alive) publishRunningKernelState(subscriber.publisher) else ZIO.unit
      tasks    <- subscriber.publisher.taskManager.list
      _        <- PublishMessage(KernelStatus(UpdatedTasks(tasks)))
      presence <- subscriber.publisher.subscribersPresent
      _        <- PublishMessage(KernelStatus(PresenceUpdate(presence.map(_._1), Nil))) // TODO: if there are tons of subscribers, disable presence stuff
      _        <- presence.flatMap(_._2.toList).map(sel => PublishMessage(KernelStatus(sel))).sequence
    } yield ()
  }

  val handleMessage: PartialFunction[Message, RIO[SessionEnv with PublishMessage, Unit]] = {
    case upConfig @ UpdateConfig(_, _, config) =>
      for {
        _ <- subscriber.checkPermission(Permission.ModifyNotebook)
        _ <- subscriber.update(upConfig)
        _ <- subscriber.publisher.restartKernel(forceStart = false)
      } yield ()

    case NotebookUpdate(update) =>
      subscriber.checkPermission(Permission.ModifyNotebook) *> subscriber.update(update)

    case RunCell(ids) =>
      if (ids.isEmpty) ZIO.unit else {
        ids.map(id => subscriber.checkPermission(Permission.ExecuteCell(_, id))).reduce(_ *> _) *>
          ids.map(id => subscriber.publisher.queueCell(id)).sequence.flatMap(_.sequence).unit
      }

    case req@CompletionsAt(id, pos, _) => for {
      completions <- subscriber.publisher.completionsAt(id, pos)
      _           <- PublishMessage(req.copy(completions = ShortList(completions)))
    } yield ()

    case req@ParametersAt(id, pos, _) => for {
      signatures <- subscriber.publisher.parametersAt(id, pos)
      _          <- PublishMessage(req.copy(signatures = signatures))
    } yield ()

    case KernelStatus( _) => for {
      status <- subscriber.publisher.kernelStatus()
      _      <- PublishMessage(KernelStatus(status))
    } yield ()

    case StartKernel(StartKernel.NoRestart)   => subscriber.publisher.kernel.unit
    case StartKernel(StartKernel.WarmRestart) => ??? // TODO
    case StartKernel(StartKernel.ColdRestart) => subscriber.publisher.restartKernel(true)
    case StartKernel(StartKernel.Kill)        => subscriber.publisher.killKernel()

    case req@HandleData(handleType, handle, count, _) => for {
      kernel <- subscriber.publisher.kernel
      data   <- kernel.getHandleData(handleType, handle, count).provide(streamingHandles).mapError(err => Error(0, err)).either
      _      <- PublishMessage(req.copy(data = data))
    } yield ()

    case req @ ModifyStream(fromHandle, ops, _) => for {
      kernel  <- subscriber.publisher.kernel
      newRepr <- kernel.modifyStream(fromHandle, ops).provide(streamingHandles)
      _       <- PublishMessage(req.copy(newRepr = newRepr))
    } yield ()

    case req @ ReleaseHandle(handleType, handleId) => for {
      kernel <- subscriber.publisher.kernel
      _      <- kernel.releaseHandle(handleType, handleId).provide(streamingHandles)
      _      <- PublishMessage(req)
    } yield ()

    case CancelTasks(path) => subscriber.publisher.cancelAll()

    case ClearOutput() => for {
      publish    <- subscriber.publisher.versionedNotebook.modify {
        case (ver, notebook) =>
          val (newCells, cellIds) = notebook.cells.foldRight((List.empty[NotebookCell], List.empty[CellID])) {
            case (cell, (cells, ids)) if cell.results.nonEmpty => (cell.copy(results = ShortList(Nil)) :: cells, cell.id :: ids)
            case (cell, (cells, ids)) => (cell :: cells, ids)
          }

          val updates = cellIds.map(id => PublishMessage(CellResult(id, ClearResults()))).sequence.unit
          (ver -> notebook.copy(cells = ShortList(newCells)), updates)
      }
        _          <- publish
    } yield ()

    case nv @ NotebookVersion(path, _) => for {
      versioned  <- subscriber.publisher.latestVersion
        _          <- PublishMessage(nv.copy(globalVersion = versioned._1))
    } yield ()

    case CurrentSelection(cellID, range) => subscriber.setSelection(cellID, range)
  }

}

object NotebookSession {
  def apply(path: String): ZIO[SessionEnv with NotebookManager, Throwable, NotebookSession] = for {
    publisher        <- NotebookManager.open(path)
    input            <- Queue.unbounded[Task, WebSocketFrame]
    output           <- Queue.unbounded[Task, WebSocketFrame]
    publishMessage   <- Env.add[SessionEnv with NotebookManager](PublishMessage.of(Publish(output).contraFlatMap(toFrame)))
    subscriber       <- publisher.subscribe()
    sessionId        <- ZIO.effectTotal(sessionId.getAndIncrement())
    streamingHandles <- Env.enrichM[BaseEnv](StreamingHandles.make(sessionId))
    closed           <- Promise.make[Throwable, Unit]
    session           = new NotebookSession(subscriber, streamingHandles, closed, input, output, publishMessage)
    _                <- subscriber.closed.await.flatMap(_ => session.close()).fork
  } yield session
}