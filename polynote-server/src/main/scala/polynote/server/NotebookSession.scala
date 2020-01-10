package polynote.server

import cats.{Applicative, MonadError}
import cats.effect.Concurrent
import cats.syntax.traverse._
import cats.instances.list._
import fs2.Stream
import fs2.concurrent.Queue
import org.http4s.Response
import org.http4s.server.websocket.WebSocketBuilder
import org.http4s.websocket.WebSocketFrame
import polynote.kernel.{BaseEnv, ClearResults, GlobalEnv, StreamOps, StreamingHandles, UpdatedTasks}
import polynote.kernel.environment.{Env, PublishMessage}
import polynote.kernel.logging.Logging
import polynote.kernel.util.Publish
import polynote.messages.{CancelTasks, CellID, CellResult, ClearOutput, CompletionsAt, Error, HandleData, KernelStatus, Message, ModifyStream, NotebookCell, NotebookUpdate, NotebookVersion, ParametersAt, ReleaseHandle, RunCell, ShortList, StartKernel, UpdateConfig}
import polynote.server.SocketSession.sessionId
import polynote.server.auth.IdentityProvider.checkPermission
import polynote.server.auth.Permission
import zio.{Promise, RIO, Task, UIO, URIO, ZIO}
import zio.interop.catz.implicits.ioTimer

import scala.concurrent.duration.{Duration, SECONDS}


class NotebookSession(
  subscriber: KernelSubscriber,
  streamingHandles: StreamingHandles with BaseEnv,
  closed: Promise[Throwable, Unit],
  input: Queue[Task, WebSocketFrame],
  output: Queue[Task, WebSocketFrame],
  publishMessage: PublishMessage
) {

  def close(): UIO[Unit] = closed.succeed(()).unit

  lazy val toResponse: ZIO[SessionEnv, Throwable, Response[Task]] = for {
    _         <- sendNotebookInfo()
    processor <- process(input, output)
    fiber     <- processor.interruptWhen(closed.await.either).compile.drain.ignore.fork
    keepalive <- Stream.awakeEvery[Task](Duration(10, SECONDS)).map(_ => WebSocketFrame.Ping()).through(output.enqueue).compile.drain.ignore.fork
    response  <- WebSocketBuilder[Task].build(
      output.dequeue.terminateAfter(_.isInstanceOf[WebSocketFrame.Close]) ++ Stream.eval(close()).drain,
      input.enqueue,
      onClose = keepalive.interrupt *> fiber.interrupt.unit *> close())
  } yield response

  def process(input: Queue[Task, WebSocketFrame], output: Queue[Task, WebSocketFrame]): URIO[SessionEnv, Stream[Task, Unit]] = {
    Env.enrich[SessionEnv](publishMessage).map {
      env => input.dequeue.flatMap {
        case WebSocketFrame.Close(_) => Stream.eval(output.enqueue1(WebSocketFrame.Close())).drain
        case WebSocketFrame.Binary(data, true) => Stream.eval(Message.decode[Task](data))
        case _ => Stream.empty
      }.evalMap {
        message =>
          handleMessage.applyOrElse(message, msg => Logging.warn(s"Unhandled message ${msg.getClass.getName}"))
            .supervised.catchAll {
              err => Logging.error("Kernel error", err) *> PublishMessage(Error(0, err))
            }.provide(env).fork.unit
      }
    }
  }

  private def sendNotebookInfo() = {
    def publishRunningKernelState(publisher: KernelPublisher) = for {
      kernel <- publisher.kernel
        _      <- kernel.values().flatMap(_.filter(_.sourceCell < 0).map(rv => PublishMessage(CellResult(path, rv.sourceCell, rv))).sequence)
        _      <- kernel.info().map(KernelStatus("", _)) >>= PublishMessage.apply
    } yield ()

    for {
      notebook <- subscriber.notebook()
        _        <- PublishMessage(notebook)
        status   <- subscriber.publisher.kernelStatus()
        _        <- PublishMessage(KernelStatus("", status))
        _        <- if (status.alive) publishRunningKernelState(subscriber.publisher) else ZIO.unit
        tasks    <- subscriber.publisher.taskManager.list
        _        <- PublishMessage(KernelStatus("", UpdatedTasks(tasks)))
    } yield ()
  }

  val handleMessage: PartialFunction[Message, RIO[SessionEnv with PublishMessage, Unit]] = {
    case upConfig @ UpdateConfig(path, _, _, config) =>
      for {
        _ <- checkPermission(Permission.ModifyNotebook(path))
        _ <- subscriber.update(upConfig)
        _ <- subscriber.publisher.restartKernel(forceStart = false)
      } yield ()

    case NotebookUpdate(update) =>
      checkPermission(Permission.ModifyNotebook(update.notebook)) *> subscriber.update(update)

    case RunCell(path, ids) =>
      if (ids.isEmpty) ZIO.unit else {
        ids.map(id => checkPermission(Permission.ExecuteCell(path, id))).reduce(_ *> _) *>
          ids.map(id => subscriber.publisher.queueCell(id)).sequence.flatMap(_.sequence).unit
      }

    case req@CompletionsAt(notebook, id, pos, _) => for {
      completions <- subscriber.publisher.completionsAt(id, pos)
      _           <- PublishMessage(req.copy(completions = ShortList(completions)))
    } yield ()

    case req@ParametersAt(notebook, id, pos, _) => for {
      signatures <- subscriber.publisher.parametersAt(id, pos)
      _          <- PublishMessage(req.copy(signatures = signatures))
    } yield ()

    case KernelStatus(path, _) => for {
      status <- subscriber.publisher.kernelStatus()
      _      <- PublishMessage(KernelStatus(path, status))
    } yield ()

    case StartKernel(path, StartKernel.NoRestart)   => subscriber.publisher.kernel.unit
    case StartKernel(path, StartKernel.WarmRestart) => ??? // TODO
    case StartKernel(path, StartKernel.ColdRestart) => subscriber.publisher.restartKernel(true)
    case StartKernel(path, StartKernel.Kill)        => subscriber.publisher.killKernel()

    case req@HandleData(path, handleType, handle, count, _) => for {
      kernel     <- subscriber.publisher.kernel
        data       <- kernel.getHandleData(handleType, handle, count).provide(streamingHandles).mapError(err => Error(0, err)).either
        _          <- PublishMessage(req.copy(data = data))
    } yield ()

    case req @ ModifyStream(path, fromHandle, ops, _) => for {
      kernel     <- subscriber.publisher.kernel
        newRepr    <- kernel.modifyStream(fromHandle, ops).provide(streamingHandles)
        _          <- PublishMessage(req.copy(newRepr = newRepr))
    } yield ()

    case req @ ReleaseHandle(path, handleType, handleId) => for {
      kernel     <- subscriber.publisher.kernel
        newRepr    <- kernel.releaseHandle(handleType, handleId).provide(streamingHandles)
        _          <- PublishMessage(req)
    } yield ()

    case CancelTasks(path) => subscriber.publisher.cancelAll()

    case ClearOutput(path) => for {
      publish    <- subscriber.publisher.versionedNotebook.modify {
        case (ver, notebook) =>
          val (newCells, cellIds) = notebook.cells.foldRight((List.empty[NotebookCell], List.empty[CellID])) {
            case (cell, (cells, ids)) if cell.results.nonEmpty => (cell.copy(results = ShortList(Nil)) :: cells, cell.id :: ids)
            case (cell, (cells, ids)) => (cell :: cells, ids)
          }

          val updates = cellIds.map(id => PublishMessage(CellResult(path, id, ClearResults()))).sequence.unit
          (ver -> notebook.copy(cells = ShortList(newCells)), updates)
      }
        _          <- publish
    } yield ()

    case nv @ NotebookVersion(path, _) => for {
      versioned  <- subscriber.publisher.latestVersion
        _          <- PublishMessage(nv.copy(globalVersion = versioned._1))
    } yield ()
  }

}

object NotebookSession {
  def apply(path: String) = for {
    publisher        <- NotebookManager.open(path)
    input            <- Queue.unbounded[Task, WebSocketFrame]
    output           <- Queue.unbounded[Task, WebSocketFrame]
    publishMessage   <- Env.add[BaseEnv with GlobalEnv with NotebookManager](PublishMessage.of(Publish(output).contraFlatMap(toFrame)))
    subscriber       <- publisher.subscribe()
    sessionId        <- ZIO.effectTotal(sessionId.getAndIncrement())
    streamingHandles <- Env.enrichM[BaseEnv](StreamingHandles.make(sessionId))
    closed           <- Promise.make[Throwable, Unit]
  } yield new NotebookSession(subscriber, streamingHandles, closed, input, output, publishMessage)
}