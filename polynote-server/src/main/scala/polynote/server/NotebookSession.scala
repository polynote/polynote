package polynote.server

import java.util.concurrent.atomic.AtomicInteger

import cats.instances.list._
import cats.syntax.either._
import cats.syntax.traverse._
import polynote.kernel.{BaseEnv, ClearResults, GlobalEnv, PresenceUpdate, Running, StreamThrowableOps, StreamingHandles, TaskInfo, UpdatedTasks}
import polynote.kernel.environment.{Env, PublishMessage}
import polynote.kernel.util.Publish
import polynote.messages._
import polynote.server.auth.Permission
import uzhttp.HTTPError
import HTTPError.NotFound
import polynote.kernel.logging.Logging
import uzhttp.websocket.Frame
import zio.stream.{Stream, Take}
import zio.ZQueue
import zio.stream.ZStream
import zio.{Promise, RIO, Task, UIO, ZIO, ZLayer}


class NotebookSession(subscriber: KernelSubscriber, streamingHandles: StreamingHandles.Service) {

  private val streamingHandlesLayer = ZLayer.succeed(streamingHandles)

  val handleMessage: Message => RIO[SessionEnv with PublishMessage, Unit] = {
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
      data   <- kernel.getHandleData(handleType, handle, count).provideSomeLayer[BaseEnv](streamingHandlesLayer).mapError(err => Error(0, err)).either
      _      <- PublishMessage(req.copy(data = data))
    } yield ()

    case req @ ModifyStream(fromHandle, ops, _) => for {
      kernel  <- subscriber.publisher.kernel
      newRepr <- kernel.modifyStream(fromHandle, ops).provideSomeLayer[BaseEnv](streamingHandlesLayer)
      _       <- PublishMessage(req.copy(newRepr = newRepr))
    } yield ()

    case req @ ReleaseHandle(handleType, handleId) => for {
      kernel <- subscriber.publisher.kernel
      _      <- kernel.releaseHandle(handleType, handleId).provideSomeLayer[BaseEnv](streamingHandlesLayer)
      _      <- PublishMessage(req)
    } yield ()

    case CancelTasks(path) => subscriber.publisher.cancelAll()

    case ClearOutput() => for {
      cells <- subscriber.publisher.versionedNotebook.clearAllResults()
      _     <- ZIO.foreach_(cells)(id => PublishMessage(CellResult(id, ClearResults())))
    } yield ()

    case nv @ NotebookVersion(path, _) => for {
      versioned  <- subscriber.publisher.latestVersion
      _          <- PublishMessage(nv.copy(globalVersion = versioned._1))
    } yield ()

    case CurrentSelection(cellID, range) => subscriber.setSelection(cellID, range)

    // TODO: remove once Global and Notebook messages are separated, to give back exhaustivity checking
    case _ => ZIO.unit
  }

  private def sendStatus: RIO[BaseEnv with GlobalEnv with PublishMessage, Unit] = subscriber.publisher.kernelStatus().flatMap {
    status => PublishMessage(KernelStatus(status)) *> ZIO.when(status.alive) {
      for {
        kernel <- subscriber.publisher.kernel
        values <- kernel.values()
        _      <- ZIO.foreach(values.filter(_.sourceCell < 0))(value => PublishMessage(CellResult(value.sourceCell, value)))
        _      <- kernel.info().map(KernelStatus(_)) >>= PublishMessage
      } yield ()
    }
  }

  private def sendTasks: RIO[BaseEnv with PublishMessage, Unit] =
    subscriber.publisher.tasks().map(tasks => KernelStatus(UpdatedTasks(tasks))) >>= PublishMessage

  private def sendPresence: RIO[PublishMessage, Unit] = subscriber.publisher.subscribersPresent.flatMap {
    presence =>
      PublishMessage(KernelStatus(PresenceUpdate(presence.filterNot(_._1.id == subscriber.id).map(_._1), Nil))) *>
        ZIO.foreach_(presence.flatMap(_._2.toList).map(sel => KernelStatus(sel)))(PublishMessage)
  }

  // Send the results, along with some progress updates based on how many results there are
  private def sendCellResults(results: List[CellResult], taskInfo: TaskInfo): ZIO[PublishMessage, Throwable, Unit] = {
    val sendResults = ZIO.when(results.nonEmpty) {
      (results.size / 10) match {
        case 0 => ZIO.foreach_(results)(PublishMessage)
        case chunkSize =>
          val chunks = results.sliding(chunkSize, chunkSize).toList
          val messages = chunks.head ::: chunks.tail.zipWithIndex.flatMap {
            case (chunk, index) => KernelStatus(UpdatedTasks(List(taskInfo.progress(0.5 + 0.05 * index)))) :: chunk
          }

          ZIO.foreach_(messages)(PublishMessage)
      }
    }
    sendResults *> PublishMessage(KernelStatus(UpdatedTasks(List(taskInfo.completed))))
  }

  // First send the notebook without any results (because they're large) and then send the individual results
  // to make notebook loading more incremental.
  private def sendNotebook: RIO[BaseEnv with GlobalEnv with PublishMessage, Unit] = for {
    nb      <- subscriber.notebook
    taskInfo = TaskInfo(nb.path, s"Loading notebook", s"Loading ${nb.path}", Running)
    _       <- PublishMessage(KernelStatus(UpdatedTasks(List(taskInfo))))
    _       <- PublishMessage(nb.withoutResults)
    _       <- PublishMessage(KernelStatus(UpdatedTasks(List(taskInfo.progress(0.5)))))
    _       <- sendCellResults(nb.results, taskInfo)
  } yield ()

  def sendNotebookInfo: RIO[SessionEnv with PublishMessage, Unit] =
    subscriber.checkPermission(Permission.ReadNotebook).flatMap { _ =>
      sendNotebook *> sendStatus *> sendTasks *> sendPresence
    }.catchAll(err => PublishMessage(Error(0, err)))
}

object NotebookSession {

  def stream(path: String, input: Stream[Throwable, Frame]): ZIO[SessionEnv with NotebookManager, HTTPError, Stream[Throwable, Frame]] = {
    for {
      _                <- NotebookManager.assertValidPath(path)
      publisher        <- NotebookManager.open(path).orElseFail(NotFound(path))
      output           <- ZQueue.unbounded[Take[Nothing, Message]]
      publishMessage   <- Env.add[SessionEnv with NotebookManager](Publish(output): Publish[Task, Message])
      subscriber       <- publisher.subscribe().orDie
      sessionId        <- nextSessionId
      streamingHandles <- StreamingHandles.make(sessionId).orDie
      closed           <- Promise.make[Throwable, Unit]
      handler           = new NotebookSession(subscriber, streamingHandles)
      env              <- ZIO.environment[SessionEnv with NotebookManager with PublishMessage]
      close             = closeQueueIf(closed, output) *> subscriber.close()
      _                <- handler.sendNotebookInfo
    } yield parallelStreams(
      toFrames(ZStream.fromQueue(output).unTake),
      input.handleMessages(close) {
        msg => handler.handleMessage(msg).catchAll {
          err => Logging.error(err) *> output.offer(Take.Value(Error(0, err)))
        }.fork.as(None)
      },
      keepaliveStream(closed)
    ).provide(env)
  }.catchAll {
    case err: HTTPError => ZIO.fail(err)
    case err => Logging.error(err) *> ZIO.fail(HTTPError.InternalServerError(err.getMessage, Some(err)))
  }

  private val sessionId = new AtomicInteger(0)
  def nextSessionId: UIO[Int] = ZIO.effectTotal(sessionId.getAndIncrement())
}