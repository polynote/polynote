package polynote
package server

import java.util.concurrent.atomic.AtomicInteger
import polynote.kernel.util.{Publish, RefMap, UPublish}
import polynote.kernel.environment.{CurrentNotebook, PublishMessage, PublishResult, PublishStatus}
import polynote.messages.{CellID, CellResult, ContentEdit, ContentEdits, Error, DefinitionLocation, Message, Notebook, NotebookUpdate, ShortList, UpdateCell}
import polynote.kernel.{BaseEnv, CellEnv, CellStatusUpdate, ClearResults, Completion, GlobalEnv, Kernel, KernelBusyState, KernelError, KernelStatusUpdate, NotebookRef, Presence, PresenceSelection, PresenceUpdate, Result, Signatures, TaskB, TaskG, TaskInfo}
import polynote.util.VersionBuffer
import zio.{Has, Hub, Promise, Queue, RIO, RManaged, Ref, Schedule, Semaphore, Task, UIO, ULayer, UManaged, URIO, ZHub, ZIO, ZLayer}
import KernelPublisher.{GlobalVersion, SubscriberId}
import polynote.kernel.interpreter.InterpreterState
import polynote.kernel.logging.Logging
import polynote.kernel.task.TaskManager
import polynote.server.auth.UserIdentity
import zio.clock.Clock
import zio.duration._
import zio.stream.{Take, UStream, ZStream}

import java.util.concurrent.TimeUnit
import scala.collection.mutable.ListBuffer

class KernelPublisher private (
  val versionedNotebook: NotebookRef,
  private[server] val updateQueue: Queue[(SubscriberId, NotebookUpdate)], // visible for testing
  val broadcastUpdates: Hub[(SubscriberId, NotebookUpdate)],
  status: Hub[Take[Nothing, KernelStatusUpdate]],
  val cellResults: ZHub[Any, Any, Throwable, Throwable, CellResult, CellResult],
  val taskManager: TaskManager.Service,
  kernelRef: Ref[Option[Kernel]],
  interpreterState: InterpreterState.Service,
  kernelStarting: Semaphore,
  queueingCell: Semaphore,
  subscribing: Semaphore,
  kernelFactory: Kernel.Factory.Service,
  val closed: Promise[Throwable, Unit],
  subscribers: RefMap[Int, KernelSubscriber]
) {
  val publishStatus: UPublish[KernelStatusUpdate] = status.contramap[KernelStatusUpdate](u => Take.single(u))

  private val taskManagerLayer: ULayer[TaskManager] = ZLayer.succeed(taskManager)

  private val baseLayer: ZLayer[Any, Nothing, CurrentNotebook with TaskManager with PublishStatus] =
    ZLayer.succeedMany(Has(versionedNotebook) ++ Has(publishStatus)) ++ taskManagerLayer

  private def cellLayer(cellID: CellID, tapResults: Option[Result => Task[Unit]] = None): ZLayer[Logging, Nothing, PublishResult] =
    ZLayer.fromService {
      logging: Logging.Service =>
        tapResults.foldLeft(Publish(cellResults).contramap[Result](result => CellResult(cellID, result)))(_ tap _)
          .catchAll(logging.error(None, _))
  }

  private def cellEnv(cellID: CellID, tapResults: Option[Result => Task[Unit]] = None): ZLayer[Logging, Nothing, CellEnv] =
    baseLayer ++ ZLayer.succeed(interpreterState) ++ cellLayer(cellID, tapResults)

  private def depEnv: ZLayer[Logging, Nothing, CellEnv] =
    baseLayer ++ ZLayer.succeed(interpreterState) ++ PublishResult.ignore

  private val kernelFactoryEnv: ZLayer[BaseEnv, Nothing, CellEnv] =
    cellEnv(CellID(-1))

  private val nextSubscriberId = new AtomicInteger(0)

  def latestVersion: Task[(GlobalVersion, Notebook)] = versionedNotebook.getVersioned

  def subscribersPresent: UIO[List[(Presence, Option[PresenceSelection])]] = subscribers.values.flatMap {
    subscribers => ZIO.foreachPar(subscribers) {
      subscriber => subscriber.getSelection.map {
        presenceSelection => subscriber.presence -> presenceSelection
      }
    }
  }

  def update(subscriberId: SubscriberId, update: NotebookUpdate): UIO[Unit] =
    updateQueue.offer((subscriberId, update)).unit //.repeatUntil(identity).unit

  def subscribeUpdates: UManaged[UStream[(SubscriberId, NotebookUpdate)]] =
    broadcastUpdates.subscribe.map(queue => ZStream.fromQueue(queue))

  def subscribeStatus: ZStream[Any, Throwable, KernelStatusUpdate] =
    ZStream.fromHub(status).flattenTake

  private def handleKernelClosed(kernel: Kernel): TaskB[Unit] =
    kernel.awaitClosed.catchAllCause {
      err => publishStatus.publish(KernelError(err.squash)) *> Logging.error(s"Kernel closed with error", err)
    } *>
      publishStatus.publish(KernelBusyState(busy = false, alive = false)) *>
      Logging.info("Kernel closed") *>
      kernelRef.set(None) *>
      closeIfNoSubscribers

  val kernelIfStarted: UIO[Option[Kernel]] = kernelRef.get

  val kernel: RIO[BaseEnv with GlobalEnv, Kernel] = kernelRef.get.flatMap {
    case Some(kernel) =>
      ZIO.succeed(kernel)
    case None =>
      kernelStarting.withPermit {
        kernelRef.get.flatMap {
          case Some(kernel) =>
            ZIO.succeed(kernel)
          case None =>
            taskManager.run[BaseEnv with GlobalEnv, Kernel]("StartKernel", "Starting kernel") {
              for {
                kernel <- createKernel()
                _      <- kernelRef.set(Some(kernel))
                _      <- handleKernelClosed(kernel).forkDaemon
                _      <- kernel.init().provideSomeLayer[BaseEnv with GlobalEnv](kernelFactoryEnv)
                _      <- kernel.info() >>= publishStatus.publish
              } yield kernel
            }.forkDaemon.flatMap(_.join)
            // Note: this forkDaemon is so that interruptions coming from client disconnect won't interrupt the
            //       starting kernel.
        }
      }.tapError {
        err => Logging.error("Error starting kernel; shutting it down", err) *> shutdownKernel() *> publishStatus.publish(KernelError(err))
      }
  }

  def killKernel(): RIO[BaseEnv with GlobalEnv, Unit] = kernelRef.get.flatMap {
    case None => ZIO.unit
    case Some(kernel) => kernelStarting.withPermit(kernel.shutdown().forkDaemon *> kernelRef.set(None))
  }

  def restartKernel(forceStart: Boolean): RIO[BaseEnv with GlobalEnv, Unit] = kernelRef.get.flatMap {
    case None if forceStart => kernel.unit
    case None => ZIO.unit
    case Some(_) => killKernel() *> this.kernel.unit
  }

  def queueCell(cellID: CellID): RIO[BaseEnv with GlobalEnv, Task[Unit]] = queueingCell.withPermit {

    def writeResult(result: Result) = versionedNotebook.addResult(cellID, result)

    for {
      kernel   <- kernel
      result   <- kernel.queueCell(cellID).provideSomeLayer[BaseEnv with GlobalEnv](cellEnv(cellID, tapResults = Some(writeResult)))
    } yield result
  }

  def completionsAt(cellID: CellID, pos: Int): RIO[BaseEnv with GlobalEnv, List[Completion]] = for {
    kernel      <- kernel
    completions <- kernel.completionsAt(cellID, pos).provideSomeLayer[BaseEnv with GlobalEnv](cellEnv(cellID))
  } yield completions

  def parametersAt(cellID: CellID, pos: Int): RIO[BaseEnv with GlobalEnv, Option[Signatures]] = for {
    kernel      <- kernel
    signatures  <- kernel.parametersAt(cellID, pos).provideSomeLayer[BaseEnv with GlobalEnv](cellEnv(cellID))
  } yield signatures

  def goToDefinition(cellID: Either[String, CellID], pos: Int): RIO[BaseEnv with GlobalEnv, List[DefinitionLocation]] = kernelIfStarted.flatMap {
    case None => ZIO.succeed(Nil)
    case Some(kernel) =>
      val env = cellID.fold(_ => depEnv, id => cellEnv(id))
      kernel.goToDefinition(cellID, pos).provideSomeLayer[BaseEnv with GlobalEnv](env)
  }

  def kernelStatus(): TaskB[KernelBusyState] = for {
    kernelOpt <- kernelRef.get
    busyState <- kernelOpt.fold[TaskB[KernelBusyState]](ZIO.succeed(KernelBusyState(busy = false, alive = false)).absorb)(_.status())
  } yield busyState

  def cancelAll(): TaskB[Unit] = {
    val cancelKernelTasks = for {
      kernel <- kernelRef.get.get
      _      <- kernel.cancelAll().provideSomeLayer[BaseEnv](baseLayer)
    } yield ()

    taskManager.cancelAll() *> cancelKernelTasks
  }.ignore

  def cancelTask(taskId: String): TaskB[Unit] = {
    val cancelKernelTasks = for {
      kernel <- kernelRef.get.get
      _      <- kernel.cancelTask(taskId).provideSomeLayer[BaseEnv](baseLayer)
    } yield ()

    taskManager.cancelTask(taskId) *> cancelKernelTasks
  }.ignore

  def clearResults() = versionedNotebook.clearAllResults().flatMap {
    clearedCells =>
      ZIO.foreach_(clearedCells)(id => cellResults.publish(CellResult(id, ClearResults())))
  }

  def tasks(): TaskB[List[TaskInfo]] =
    ZIO.ifM(kernelStarting.available.map(_ == 0))(
      taskManager.list,
      kernelRef.get.get.flatMap {
        kernel => kernel.tasks().provideSomeLayer[BaseEnv](baseLayer)
      } orElse taskManager.list
    )

  // TODO: A bit ugly. There's probably a better way to keep track of cell status.
  private val extract = """Cell (\d*)""".r
  def statuses: TaskB[List[CellStatusUpdate]] = tasks().map(tasks => tasks.flatMap {
    task =>
      task.id match {
        case extract(cellId) =>
          List(CellStatusUpdate(cellId.toShort, task.status))
        case _ => Nil
      }
  })

  def subscribe(): RManaged[BaseEnv with GlobalEnv with PublishMessage with UserIdentity, KernelSubscriber] =
    for {
      _            <- ZIO.whenM(closed.isDone)(ZIO.fail(PublisherClosed)).toManaged_
      subscriberId <- ZIO.effectTotal(nextSubscriberId.getAndIncrement()).toManaged_
      updates      <- subscribeUpdates
      subscriber   <- KernelSubscriber(subscriberId, updates, this).toManaged(_.close())
      _            <- subscribing.withPermit(subscribers.put(subscriberId, subscriber)).toManaged(_ => removeSubscriber(subscriberId))
      _            <- publishStatus.publish(PresenceUpdate(List(subscriber.presence), Nil)).toManaged_
      _            <- subscriber.selections.mapM(publishStatus.publish).runDrain.forkManaged
    } yield subscriber

  private def closeIfNoSubscribers: TaskB[Unit] =
    ZIO.whenM(kernelStarting.withPermit(kernelRef.get.map(_.isEmpty)) && subscribers.isEmpty) {
      ZIO.unlessM(closed.isDone) {
        for {
          path <- latestVersion.map(_._2.path)
            _ <- Logging.info(s"Closing $path (idle with no more subscribers)")
            _ <- close()
        } yield ()
      }
    }

  private def removeSubscriber(id: Int): URIO[BaseEnv with Clock, Unit] = subscribing.withPermit {
    for {
      subscriber <- subscribers.get(id).get.orElseFail(new NoSuchElementException(s"Subscriber $id does not exist"))
      _          <- ZIO.unlessM(subscriber.closed.isDone)(subscriber.close())
      _          <- subscribers.remove(id)
      _          <- publishStatus.publish(PresenceUpdate(Nil, List(id)))
    } yield ()
  }.catchAll(Logging.error) *> closeIfNoSubscribers.delay(5.seconds).forkDaemon.unit

  def close(): TaskB[Unit] = ZIO.unlessM(closed.isDone) {
    broadcastUpdates.shutdown *> closed.succeed(()).unit *>
      shutdownKernel() *>
      taskManager.shutdown() *>
      versionedNotebook.close() *>
      status.publish(Take.end) *>
      subscribers.values.flatMap(subs => ZIO.foreachPar_(subs)(_.close())).unit *>
      status.shutdown
  }

  private def createKernel(): TaskG[Kernel] = kernelFactory()
    .provideSomeLayer[BaseEnv with GlobalEnv](kernelFactoryEnv)

  private def shutdownKernel() = kernelStarting.withPermit {
    for {
      kernelOpt <- kernelRef.get
      _         <- kernelOpt match {
        case Some(kernel) => kernel.shutdown().ensuring(kernelRef.set(None))
        case None         => ZIO.unit
      }
    } yield ()
  }
}

object KernelPublisher {

  type GlobalVersion = Int
  type SubscriberId  = Int
  
  def rebaseAllUpdates(
    update: NotebookUpdate,
    prev: List[(SubscriberId, NotebookUpdate)]
  ): (NotebookUpdate, List[(SubscriberId, NotebookUpdate)]) = update match {
    case UpdateCell(_, _, _, ContentEdits(ShortList.Nil), _) => (update, Nil)
    case self@UpdateCell(_, _, id, myEdits, _) =>
      val conflicting = prev.collect {
        case (subscriber, update@UpdateCell(_, _, `id`, _, _)) => (subscriber, update)
      }

      val (result, updatedPrev) = conflicting.foldLeft((myEdits, List.empty[(SubscriberId, UpdateCell)])) {
        case ((myEdits, newUpdates), (subscriberId, nextUpdate)) =>
          val (myRebased, theirRebased) = myEdits.rebaseBoth(nextUpdate.edits)
          (myRebased, (subscriberId, nextUpdate.copy(edits = ContentEdits(theirRebased))) :: newUpdates)
      }

      self.copy(edits = result) -> updatedPrev.reverse

    case _ => (prev.foldLeft(update) { case (rebased, (_, next)) => rebased.rebase(next) }, Nil)
  }

  /**
    * Given the versioned notebook's current value, and a buffer of previous versioned updates, and a publisher of
    * canonical updates, rebase the given update to the latest global version, update the notebook reference, and
    * publish the rebased update.
    */
  def applyUpdate(
    versionRef: NotebookRef,
    interpState: InterpreterState.Service,
    versions: SubscriberUpdateBuffer,
    publishUpdates: UPublish[(SubscriberId, NotebookUpdate)],
    subscribers: RefMap[SubscriberId, KernelSubscriber],
    log: ListBuffer[(Long, String)])(
    subscriberId: SubscriberId,
    update: NotebookUpdate
  ): TaskG[Unit] = interpState.updateStateWith(update) &> versionRef.getVersioned.flatMap {
    case (globalVersion, _) =>
      subscribers.get(subscriberId).someOrFail(new NoSuchElementException(s"No such subscriber $subscriberId")).flatMap {
        subscriber =>
          val time = System.currentTimeMillis()
          val logStr = new StringBuilder
          logStr ++= s"> S $update (from $subscriberId)\n"
          val rebased = if (update.globalVersion < globalVersion) {
            logStr ++= s"  Rebasing from ${update.globalVersion} to $globalVersion\n"
            versions.rebaseThrough(update, subscriberId, globalVersion, Some(logStr))
          } else update

          versionRef.updateAndGet(rebased).flatMap {
            case (nextVer, notebook) =>
              val newUpdate = (subscriberId, rebased.withVersions(nextVer, rebased.localVersion))
              subscriber.setLastGlobalVersion(nextVer) *>
                ZIO(versions.add(nextVer, newUpdate)) *>
                publishUpdates.publish(newUpdate) *>
                ZIO.effectTotal {
                  logStr ++= s"""  $newUpdate "${notebook.cells.headOption.map(_.content).getOrElse("")}""""
                  log += ((time, logStr.result()))
                }
          }

      }
  }

  // periodically clean old versions from the version buffer
  private def cleanVersionBuffer(
    subscriberMap: RefMap[SubscriberId, KernelSubscriber],
    buffer: VersionBuffer[(SubscriberId, NotebookUpdate)],
    closed: Promise[Throwable, Unit]
  ): URIO[Clock, Unit] = {
    val clean = for {
      subscribers <- subscriberMap.values
      versions    <- ZIO.foreachPar(subscribers)(_.getLastGlobalVersion)
      _           <- ZIO.when(versions.nonEmpty)(ZIO.effectTotal(buffer.discardUntil(versions.min)))
    } yield ()

    clean.repeat(Schedule.spaced(Duration(30, TimeUnit.SECONDS)).untilInputM(_ => closed.isDone)).unit
  }


  def apply(versionedRef: NotebookRef, broadcastMessage: UPublish[Message], log: ListBuffer[(Long, String)] = new ListBuffer[(Long, String)]): RIO[BaseEnv with GlobalEnv, KernelPublisher] = for {
    kernelFactory    <- Kernel.Factory.access
    closed           <- Promise.make[Throwable, Unit]
    // TODO: need to close if the versionedRef closes, hook up e.g. TreeRepository so it cascades
    updates          <- Queue.unbounded[(SubscriberId, NotebookUpdate)]
                        // TODO: replace the following with ZTopic
    broadcastUpdates <- Hub.unbounded[(SubscriberId, NotebookUpdate)]
    broadcastStatus  <- Hub.unbounded[Take[Nothing, KernelStatusUpdate]]
    broadcastResults <- Hub.unbounded[CellResult]
    taskManager      <- TaskManager(broadcastStatus.contramap[KernelStatusUpdate](a => Take.single(a)))
    versionBuffer     = new SubscriberUpdateBuffer()
    kernelStarting   <- Semaphore.make(1)
    queueingCell     <- Semaphore.make(1)
    subscribing      <- Semaphore.make(1)
    subscribers      <- RefMap.empty[SubscriberId, KernelSubscriber]
    kernel           <- Ref.make[Option[Kernel]](None)
    interpState      <- InterpreterState.empty
    publisher = new KernelPublisher(
      versionedRef,
      updates,
      broadcastUpdates,
      broadcastStatus,
      broadcastResults,
      taskManager,
      kernel,
      interpState,
      kernelStarting,
      queueingCell,
      subscribing,
      kernelFactory,
      closed,
      subscribers
    )
    env <- ZIO.environment[BaseEnv]
    // process the queued updates, rebasing as needed
    _   <- ZStream.fromQueue(updates)
      .mapM((applyUpdate(versionedRef, interpState, versionBuffer, Publish(broadcastUpdates), subscribers, log) _).tupled)
      .haltWhen(closed.await.run)
      .runDrain
      .catchAll {
        err =>
          broadcastMessage.publish(Error(0, new Exception(s"Catastrophe! An error occurred updating notebook. Editing will now be disabled.", err))) *>
            publisher.close().provide(env)
      }.forkDaemon
    _   <- cleanVersionBuffer(subscribers, versionBuffer, closed).forkDaemon
  } yield publisher
}

case object PublisherClosed extends Throwable

final class SubscriberUpdateBuffer extends VersionBuffer[(SubscriberId, NotebookUpdate)] {

  /**
    * Rebase the given update from its globalVersion through the target globalVersion.
    *
    * At each buffered global version, the given update will be rebased onto that global version, and the global version
    * will also be rebased on to the given update. Then, the stored global version will be replaced with the "leftovers"
    * of that rebase. This is because any future update received from the this client is already on top of the given
    * update, even if its globalVersion hasn't been updated. Note that this logic only affects edits to cell content
    * where the same cell was edited by both the client update and a subsequent global version (of which the client was
    * unaware).
    *
    * This is the same logic as the client's `EditBuffer` (see `edit_buffer.ts` in polynote-frontend), except that the
    * rebasing logic for equal updates are opposite: on the server side, equal updates cancel each other out, while on
    * the client side, equal updates are preserved. Equal updates can't be preserved on the server, because that would
    * result in duplicate edits affecting the final state – but equal edits must be preserved on the client, because the
    * client's edit has already affected the client's state. This has been experimentally verified to be the only
    * behavior under which concurrent editing reliably operates (see `KernelPublisherIntegrationTest` in which simulates
    * "keyboard-mashing" clients using the client-side logic replicated in the polynote-frontend's `EditBuffer`)
    *
    * The server-side logic also has additional filtering to ensure that it's not rebasing through the client's own
    * edits, and it has some optional debug logging (TODO: remove the debug logging)
    *
    * @return the rebased edit
    */
  def rebaseThrough(update: NotebookUpdate, subscriberId: SubscriberId, targetVersion: GlobalVersion, log: Option[StringBuilder] = None, updateBuffer: Boolean = true): NotebookUpdate = update match {
    case update@UpdateCell(sourceVersion, _, cellId, sourceEdits, _) =>
      synchronized {
        var index = versionIndex(sourceVersion + 1)
        if (index < 0) {
          log.foreach(_ ++= s"  No version ${sourceVersion + 1}")
          return update
        };

        val size = numVersions
        var currentVersion = versionedValueAt(index)._1
        var rebasedEdits = sourceEdits
        try {
          if (!(currentVersion <= targetVersion && index < size)) {
            log.foreach(_ ++= s"  No updates")
          }
          while (currentVersion <= targetVersion && index < size) {
            val elem = versionedValueAt(index)
            currentVersion = elem._1
            if (elem._2._1 != subscriberId) {
              val prevUpdateTuple@(_, prevUpdate) = elem._2
              prevUpdate match {
                case prevUpdate@UpdateCell(_, _, `cellId`, targetEdits, _) =>
                  val (sourceRebased, targetRebased) = rebasedEdits.rebaseBoth(targetEdits)
                  rebasedEdits = sourceRebased
                  log.foreach(_ ++= s"  $prevUpdate => $sourceRebased\n")
                  if (updateBuffer) {
                    setValueAt(index, prevUpdateTuple.copy(_2 = prevUpdate.copy(edits = ContentEdits(targetRebased))))
                  }
                case _ =>
              }
            }
            index += 1
          }
        } catch {
          case err: Throwable => err.printStackTrace()
        }
        update.copy(edits = rebasedEdits)
      }
    case update => getRange(update.globalVersion + 1, targetVersion).foldLeft(update) {
      case (accum, (_, next)) => accum.rebase(next)
    }
  }

}