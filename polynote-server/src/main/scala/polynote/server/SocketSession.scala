package polynote.server

import java.util.concurrent.atomic.AtomicInteger

import cats.{Applicative, MonadError}
import cats.effect.{Concurrent, Timer}
import cats.syntax.traverse._
import cats.instances.list._
import fs2.{Pipe, Stream}
import fs2.concurrent.Queue
import org.http4s.Response
import org.http4s.server.websocket.WebSocketBuilder
import org.http4s.websocket.WebSocketFrame
import org.http4s.websocket.WebSocketFrame.Binary
import polynote.buildinfo.BuildInfo
import polynote.kernel
import polynote.kernel.util.{Publish, RefMap}
import polynote.kernel.{BaseEnv, ClearResults, GlobalEnv, Kernel, StreamOps, StreamingHandles, TaskG, UpdatedTasks}
import polynote.kernel.environment.{Env, PublishMessage}
import polynote.kernel.interpreter.Interpreter
import polynote.kernel.logging.Logging
import polynote.messages._
import zio.{Promise, Task, RIO, ZIO}

import scala.collection.immutable.SortedMap
import scala.concurrent.duration.{Duration, SECONDS}

class SocketSession(
  handler: SessionHandler
)(implicit ev: MonadError[Task, Throwable], ev2: Concurrent[TaskG], ev3: Concurrent[Task], ev4: Timer[Task], ev5: Applicative[RIO[PublishMessage, ?]]) {

  private def toFrame(message: Message): Task[Binary] = {
    Message.encode[Task](message).map(bits => Binary(bits.toByteVector))
  }

  lazy val toResponse: TaskG[Response[Task]] = for {
    input     <- Queue.unbounded[Task, WebSocketFrame]
    output    <- Queue.unbounded[Task, WebSocketFrame]
    processor <- process(input, output)
    fiber     <- processor.interruptWhen(handler.awaitClosed).compile.drain.fork
    keepalive <- Stream.awakeEvery[Task](Duration(10, SECONDS)).map(_ => WebSocketFrame.Ping()).through(output.enqueue).compile.drain.fork
    response  <- WebSocketBuilder[Task].build(
      output.dequeue.terminateAfter(_.isInstanceOf[WebSocketFrame.Close]),
      input.enqueue,
      onClose = keepalive.interrupt *> fiber.interrupt.unit)
  } yield response

  private def process(
    input: Queue[Task, WebSocketFrame],
    output: Queue[Task, WebSocketFrame]
  ): ZIO[BaseEnv with GlobalEnv, Nothing, Stream[Task, Unit]] = Env.enrich[BaseEnv with GlobalEnv](PublishMessage.of(Publish(output).contraFlatMap(toFrame))).map {
    env =>
      Stream.eval(handshake.provide(env)).evalMap(env.publishMessage.publish1) ++ input.dequeue.flatMap {
        case WebSocketFrame.Close(_) => Stream.eval(output.enqueue1(WebSocketFrame.Close())).drain
        case WebSocketFrame.Binary(data, true) => Stream.eval(Message.decode[Task](data))
        case _ => Stream.empty
      }.evalMap {
        message =>
          handler.accept(message).supervised.catchAll {
            err =>
              Logging.error("Kernel error", err) *>
              PublishMessage(Error(0, err))
          }.provide(env).fork.unit
      }
  }

  private def handshake: TaskG[ServerHandshake] =
    ZIO.access[Interpreter.Factories](_.interpreterFactories).map {
      factories => ServerHandshake(
        (SortedMap.empty[String, String] ++ factories.mapValues(_.head.languageName)).asInstanceOf[TinyMap[TinyString, TinyString]],
        serverVersion = BuildInfo.version,
        serverCommit = BuildInfo.commit)
    }
}

class SessionHandler(
  notebookManager: NotebookManager.Service,
  subscribed: RefMap[String, KernelSubscriber],
  closed: Promise[Throwable, Unit],
  streamingHandles: StreamingHandles with BaseEnv
)(implicit ev: MonadError[Task, Throwable], ev2: Concurrent[TaskG], ev3: Concurrent[Task], ev4: Timer[Task], ev5: Applicative[RIO[PublishMessage, ?]]) {

  def accept(message: Message): RIO[BaseEnv with GlobalEnv with PublishMessage, Unit] =
    handler.applyOrElse(message, unhandled)

  def awaitClosed: ZIO[Any, Nothing, Either[Throwable, Unit]] = closed.await.either

  private def subscribe(path: String): RIO[BaseEnv with GlobalEnv with PublishMessage, KernelSubscriber] = subscribed.getOrCreate(path) {
    for {
      kernelPublisher <- notebookManager.open(path)
      subscriber      <- kernelPublisher.subscribe()
      _               <- subscriber.closed.await.flatMap(_ => subscribed.remove(path)).fork
      _               <- kernelPublisher.closed.await.flatMap(_ => subscriber.close()).fork
    } yield subscriber
  }

  private def unhandled(msg: Message): RIO[BaseEnv, Unit] = Logging.warn(s"Unhandled message type ${msg.getClass.getName}")

  private val handler: PartialFunction[Message, RIO[BaseEnv with GlobalEnv with PublishMessage, Unit]] = {
    case ListNotebooks(_) =>
      notebookManager.list().flatMap {
        notebooks => PublishMessage(ListNotebooks(notebooks.map(ShortString.apply)))
      }

    case LoadNotebook(path) =>
      def publishRunningKernelState(publisher: KernelPublisher) = for {
        kernel <- publisher.kernel
        _      <- kernel.values().flatMap(_.filter(_.sourceCell < 0).map(rv => PublishMessage(CellResult(path, rv.sourceCell, rv))).sequence)
        _      <- kernel.info().map(KernelStatus(path, _)) >>= PublishMessage.apply
      } yield ()

      subscribe(path).flatMap {
        subscriber =>
          for {
            notebook <- subscriber.notebook()
            _        <- PublishMessage(notebook)
            status   <- subscriber.publisher.kernelStatus()
            _        <- PublishMessage(KernelStatus(path, status))
            _        <- if (status.alive) publishRunningKernelState(subscriber.publisher) else ZIO.unit
            tasks    <- subscriber.publisher.taskManager.list
            _        <- PublishMessage(KernelStatus(path, UpdatedTasks(tasks)))
          } yield ()
      }

    case CreateNotebook(path, maybeUriOrContent) =>
      notebookManager.create(path, maybeUriOrContent).flatMap {
        realPath => PublishMessage(CreateNotebook(ShortString(realPath)))
      }

    case upConfig @ UpdateConfig(path, _, _, config) => for {
      subscriber <- subscribe(path)
      _          <- subscriber.update(upConfig)
      _          <- subscriber.publisher.restartKernel(forceStart = false)
    } yield ()

    case NotebookUpdate(update) =>
      subscribe(update.notebook).flatMap(_.update(update))

    case RunCell(path, ids) =>
      subscribe(path).flatMap {
        subscriber => ids.map(id => subscriber.publisher.queueCell(id)).sequence.flatMap(_.sequence).unit
      }

    case req@CompletionsAt(notebook, id, pos, _) => for {
      subscriber  <- subscribe(notebook)
      completions <- subscriber.publisher.completionsAt(id, pos)
      _           <- PublishMessage(req.copy(completions = ShortList(completions)))
    } yield ()

    case req@ParametersAt(notebook, id, pos, _) => for {
      subscriber <- subscribe(notebook)
      signatures <- subscriber.publisher.parametersAt(id, pos)
      _          <- PublishMessage(req.copy(signatures = signatures))
    } yield ()

    case KernelStatus(path, _) => for {
      subscriber <- subscribe(path)
      status     <- subscriber.publisher.kernelStatus()
      _          <- PublishMessage(KernelStatus(path, status))
    } yield ()

    case StartKernel(path, StartKernel.NoRestart)   => subscribe(path).flatMap(_.publisher.kernel).unit
    case StartKernel(path, StartKernel.WarmRestart) => ??? // TODO
    case StartKernel(path, StartKernel.ColdRestart) => subscribe(path).flatMap(_.publisher.restartKernel(true))
    case StartKernel(path, StartKernel.Kill)        => subscribe(path).flatMap(_.publisher.killKernel())

    case req@HandleData(path, handleType, handle, count, _) => for {
      subscriber <- subscribe(path)
      kernel     <- subscriber.publisher.kernel
      data       <- kernel.getHandleData(handleType, handle, count).provide(streamingHandles).mapError(err => Error(0, err)).either
      _          <- PublishMessage(req.copy(data = data))
    } yield ()

    case req @ ModifyStream(path, fromHandle, ops, _) => for {
      subscriber <- subscribe(path)
      kernel     <- subscriber.publisher.kernel
      newRepr    <- kernel.modifyStream(fromHandle, ops).provide(streamingHandles)
      _          <- PublishMessage(req.copy(newRepr = newRepr))
    } yield ()

    case req @ ReleaseHandle(path, handleType, handleId) => for {
      subscriber <- subscribe(path)
      kernel     <- subscriber.publisher.kernel
      newRepr    <- kernel.releaseHandle(handleType, handleId).provide(streamingHandles)
      _          <- PublishMessage(req)
    } yield ()

    case CancelTasks(path) => subscribe(path).flatMap(_.publisher.cancelAll())

    case ClearOutput(path) => for {
      subscriber <- subscribe(path)
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
      subscriber <- subscribe(path)
      versioned  <- subscriber.publisher.latestVersion
      _          <- PublishMessage(nv.copy(globalVersion = versioned._1))
    } yield ()

    case RunningKernels(_) => for {
      paths          <- notebookManager.listRunning()
      statuses       <- paths.map(notebookManager.status).sequence
      kernelStatuses  = paths.zip(statuses).map {
        case (path, status) => KernelStatus(ShortString(path), status)
      }
      _              <- PublishMessage(RunningKernels(kernelStatuses))
    } yield ()

    case other =>
      ZIO.unit
  }
}

object SocketSession {
  def apply()(implicit ev: MonadError[Task, Throwable], ev2: Concurrent[TaskG], ev3: Concurrent[Task], ev4: Timer[Task], ev5: Applicative[RIO[PublishMessage, ?]]): RIO[BaseEnv with NotebookManager, SocketSession] = for {
    notebookManager  <- NotebookManager.access
    subscribed       <- RefMap.empty[String, KernelSubscriber]
    closed           <- Promise.make[Throwable, Unit]
    sessionId        <- ZIO.effectTotal(sessionId.getAndIncrement())
    streamingHandles <- Env.enrichM[BaseEnv](StreamingHandles.make(sessionId))
  } yield new SocketSession(new SessionHandler(notebookManager, subscribed, closed, streamingHandles))

  private val sessionId = new AtomicInteger(0)
}
