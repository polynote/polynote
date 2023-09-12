package polynote.server

import cats.syntax.either._
import polynote.kernel.environment.{BroadcastAll, BroadcastMessage, Env, PublishMessage}
import polynote.kernel.logging.Logging
import polynote.kernel.util.{Publish, UPublish}
import polynote.kernel.{BaseEnv, Complete, GlobalEnv, PresenceUpdate, Running, StreamingHandles, TaskInfo, UpdatedTasks}
import polynote.messages._
import polynote.server.auth.Permission
import uzhttp.HTTPError
import uzhttp.HTTPError.NotFound
import uzhttp.websocket.Frame
import zio.stream.{Stream, Take, UStream, ZStream}
import zio.{Promise, RIO, UIO, ZIO, ZLayer, ZManaged, ZQueue}

import java.util.Calendar
import java.util.concurrent.atomic.AtomicInteger


class NotebookSession(
  subscriber: KernelSubscriber,
  streamingHandles: StreamingHandles.Service,
  broadcastAll: UPublish[Message]
) {

  private val streamingHandlesLayer = ZLayer.succeed(streamingHandles)

  val handleMessage: Message => RIO[SessionEnv with PublishMessage with NotebookManager, Unit] = {
    case upConfig @ UpdateConfig(_, _, config) =>
      for {
        _ <- subscriber.checkPermission(Permission.ModifyNotebook)
        _ <- subscriber.update(upConfig)
        _ <- subscriber.publisher.restartKernel(forceStart = false)
      } yield ()

    case NotebookUpdate(update) =>
      subscriber.checkPermission(Permission.ModifyNotebook) *> subscriber.update(update)

    case RunCell(ids) =>
      ZIO.unless(ids.isEmpty) {
        ZIO.foreachPar_(ids)(id => subscriber.checkPermission(Permission.ExecuteCell(_, id))) *>
          ZIO.foreach(ids.toList)(id => subscriber.publisher.queueCell(id)).flatMap {
            tasks => ZIO.collectAll_(tasks.map(_.run.uninterruptible)).forkDaemon
          }
      }

    case req@CompletionsAt(id, pos, _) => for {
      completions <- subscriber.publisher.completionsAt(id, pos)
      _           <- PublishMessage(req.copy(completions = ShortList(completions)))
    } yield ()

    case req@ParametersAt(id, pos, _) => for {
      signatures <- subscriber.publisher.parametersAt(id, pos)
      _          <- PublishMessage(req.copy(signatures = signatures))
    } yield ()

    case GoToDefinitionRequest(source, pos, reqId) =>
      for {
        definitions <- subscriber.publisher.goToDefinition(source, pos)
        _           <- PublishMessage(GoToDefinitionResponse(reqId, definitions))
      } yield ()

    case KernelStatus( _) => for {
      status <- subscriber.publisher.kernelStatus()
      _      <- PublishMessage(KernelStatus(status))
    } yield ()

    case StartKernel(StartKernel.NoRestart)   => subscriber.publisher.kernel.unit *> sendRunningKernels
    case StartKernel(StartKernel.WarmRestart) => ??? // TODO
    case StartKernel(StartKernel.ColdRestart) => subscriber.publisher.restartKernel(true) *> sendRunningKernels
    case StartKernel(StartKernel.Kill)        => subscriber.publisher.killKernel() *> sendRunningKernels

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

    case CancelTasks(path, None)         => subscriber.publisher.cancelAll()
    case CancelTasks(path, Some(taskId)) => subscriber.publisher.cancelTask(taskId)

    case ClearOutput() => subscriber.publisher.clearResults()

    case NotebookVersion(_, knownVersion) =>
      ZIO.when(knownVersion >= 0)(subscriber.updateLastGlobalVersion(knownVersion))

    case CurrentSelection(cellID, range) => subscriber.setSelection(cellID, range)

    case KeepAlive(payload) =>
      // echo received KeepAlive message back to client.
      PublishMessage(KeepAlive(payload))

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

  private def sendVersion: RIO[BaseEnv with GlobalEnv with PublishMessage, Unit] = for {
    versioned <- subscriber.publisher.latestVersion
    _ <- PublishMessage(NotebookVersion(versioned._2.path, versioned._1))
  } yield ()

  private def sendTasks: RIO[BaseEnv with PublishMessage, Unit] =
    subscriber.publisher.tasks().map(tasks => KernelStatus(UpdatedTasks(tasks))) >>= PublishMessage

  private def sendPresence: RIO[PublishMessage, Unit] = subscriber.publisher.subscribersPresent.flatMap {
    presence =>
      PublishMessage(KernelStatus(PresenceUpdate(presence.filterNot(_._1.id == subscriber.id).map(_._1), Nil))) *>
        ZIO.foreach_(presence.flatMap(_._2.toList).map(sel => KernelStatus(sel)))(PublishMessage)
  }

  private def sendCellStatuses: RIO[BaseEnv with PublishMessage, Unit] = subscriber.publisher.statuses.flatMap {
    statuses =>
      ZIO.foreach_(statuses.map(KernelStatus(_)))(PublishMessage)
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

  private def sendRunningKernels: RIO[SessionEnv with PublishMessage with NotebookManager, Unit]  =
    SocketSession.getRunningKernels.flatMap(broadcastAll.publish)

  // First send the notebook without any results (because they're large) and then send the individual results
  // to make notebook loading more incremental.
  private def sendNotebookContents: RIO[BaseEnv with GlobalEnv with PublishMessage, Unit] = for {
    nb      <- subscriber.notebook
    taskInfo = TaskInfo(nb.path, s"Loading notebook", s"Loading ${nb.path}", Running)
    _       <- PublishMessage(KernelStatus(UpdatedTasks(List(taskInfo))))
    _       <- PublishMessage(nb.withoutResults)
    _       <- PublishMessage(KernelStatus(UpdatedTasks(List(taskInfo.progress(0.5)))))
    _       <- sendCellResults(nb.results, taskInfo)
    _       <- PublishMessage(KernelStatus(UpdatedTasks(List(taskInfo.done(Complete)))))
  } yield ()

  def sendNotebook: RIO[SessionEnv with PublishMessage, Unit] =
    subscriber.checkPermission(Permission.ReadNotebook).flatMap { _ =>
      // make sure to send status first, so client knows whether this notebook is up or not.
      sendStatus *> sendVersion *> sendNotebookContents *> sendTasks *> sendPresence *> sendCellStatuses
    }.catchAll(err => PublishMessage(Error(0, err)))
}

object NotebookSession {

  def stream(path: String, input: Stream[Throwable, Frame], broadcastAll: BroadcastMessage): ZManaged[SessionEnv with NotebookManager, HTTPError, UStream[Frame]] = {
    for {
      _                <- NotebookManager.assertValidPath(path).toManaged_
      output           <- ZQueue.unbounded[Take[Nothing, Message]].toManaged_ // TODO: finalizer instead of close
      _                <- Env.addManaged[SessionEnv with NotebookManager](Publish(output))
      _                <- Env.addManaged[SessionEnv with NotebookManager with PublishMessage](broadcastAll)
      subscriber       <- NotebookManager.subscribe(path).orElseFail(NotFound(path))
      sessionId        <- nextSessionId.toManaged_
      streamingHandles <- StreamingHandles.make(sessionId).orDie.toManaged_
      closed           <- Promise.make[Throwable, Unit].toManaged_
      handler           = new NotebookSession(subscriber, streamingHandles, broadcastAll)
      env              <- ZIO.environment[SessionEnv with NotebookManager with PublishMessage].toManaged_
      _                <- handler.sendNotebook.toManaged_
    } yield parallelStreams(
      toFrames(ZStream.fromQueue(output).flattenTake),
      input.handleMessages(closeQueueIf(closed, output)) {
        msg => handler.handleMessage(msg).catchAll {
          err => Logging.error(err) *> PublishMessage(Error(0, err)) *> output.offer(Take.single(Error(0, err)))
        }.fork.as(None)
      },
      keepaliveStream(closed)
    ).catchAll {
      err => ZStream.fromEffect(Logging.error("Notebook session is closing due to error", err)).drain
    }.provide(env)
  }.catchAll {
    case err: HTTPError => ZManaged.fail(err)
    case err => Logging.error(err).toManaged_ *> ZManaged.succeed(ZStream.empty)
  }

  private val sessionId = new AtomicInteger(0)
  def nextSessionId: UIO[Int] = ZIO.effectTotal(sessionId.getAndIncrement())
}