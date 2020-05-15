package polynote
package server

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

import cats.effect.concurrent.{Ref => CatsRef}
import cats.instances.list._
import cats.syntax.traverse._
import fs2.Stream
import fs2.concurrent.{Queue, SignallingRef, Topic}
import polynote.kernel.util.{Publish, RefMap}
import polynote.kernel.environment.{CurrentNotebook, NotebookUpdates, PublishMessage, PublishResult, PublishStatus}
import polynote.messages.{CellID, CellResult, Error, Message, Notebook, NotebookUpdate, ShortList}
import polynote.kernel.{BaseEnv, CellEnv, ClearResults, Completion, ExecutionInfo, GlobalEnv, Kernel, KernelBusyState, KernelError, KernelStatusUpdate, NotebookRef, Output, Presence, PresenceSelection, PresenceUpdate, Result, ScalaCompiler, Signatures, StreamThrowableOps, TaskB, TaskG, TaskInfo}
import polynote.util.VersionBuffer
import zio.{Fiber, Has, Promise, RIO, Ref, Semaphore, Task, UIO, ZIO, ZLayer}
import KernelPublisher.{GlobalVersion, SubscriberId}
import polynote.kernel.logging.Logging
import polynote.kernel.task.TaskManager
import polynote.server.auth.UserIdentity

import scala.concurrent.duration.FiniteDuration

class KernelPublisher private (
  val versionedNotebook: NotebookRef,
  val versionBuffer: VersionBuffer[NotebookUpdate],
  publishUpdate: Publish[Task, (SubscriberId, NotebookUpdate)],
  val broadcastUpdates: Topic[Task, Option[(SubscriberId, NotebookUpdate)]],
  val status: Topic[Task, KernelStatusUpdate],
  val cellResults: Topic[Task, Option[CellResult]],
  val broadcastAll: Topic[Task, Option[Message]],
  val taskManager: TaskManager.Service,
  kernelRef: Ref[Option[Kernel]],
  kernelStarting: Semaphore,
  queueingCell: Semaphore,
  subscribing: Semaphore,
  kernelFactory: Kernel.Factory.Service,
  val closed: Promise[Throwable, Unit],
  subscribers: RefMap[Int, KernelSubscriber]
) {
  val publishStatus: Publish[Task, KernelStatusUpdate] = status

  private val baseLayer: ZLayer[Any, Nothing, CurrentNotebook with TaskManager with PublishStatus] =
    ZLayer.succeedMany(Has(versionedNotebook) ++ Has(taskManager) ++ Has(publishStatus))

  private def cellLayer(cellID: CellID, tapResults: Option[Result => Task[Unit]] = None): ZLayer[Any, Nothing, PublishResult] = {
    val publish = Publish(cellResults).contramap[Result](result => Some(CellResult(cellID, result)))
    val env = tapResults.fold(publish)(fn => publish.tap(fn))
    ZLayer.succeed(env)
  }

  private def cellEnv(cellID: CellID, tapResults: Option[Result => Task[Unit]] = None): ZLayer[Any, Nothing, CellEnv] =
    baseLayer ++ cellLayer(cellID, tapResults)

  private val kernelFactoryEnv: ZLayer[Any, Nothing, CellEnv with NotebookUpdates] = {
    val updates = broadcastUpdates.subscribe(128).unNone.map(_._2)
    cellEnv(CellID(-1)) ++ ZLayer.succeed(updates)
  }

  private val nextSubscriberId = new AtomicInteger(0)

  def latestVersion: Task[(GlobalVersion, Notebook)] = versionedNotebook.getVersioned

  def subscribersPresent: UIO[List[(Presence, Option[PresenceSelection])]] = subscribers.values.flatMap {
    subscribers => subscribers.map {
      subscriber => subscriber.getSelection.map {
        presenceSelection => subscriber.presence -> presenceSelection
      }
    }.sequence
  }

  def update(subscriberId: SubscriberId, update: NotebookUpdate): Task[Unit] =
    publishUpdate.publish1((subscriberId, update))

  private def handleKernelClosed(kernel: Kernel): TaskB[Unit] =
    kernel.awaitClosed.catchAllCause {
      err => publishStatus.publish1(KernelError(err.squash)) *> Logging.error(s"Kernel closed with error", err)
    } *> Logging.info("Kernel closed") *> kernelRef.set(None) *> publishStatus.publish1(KernelBusyState(busy = false, alive = false))

  val kernel: RIO[BaseEnv with GlobalEnv, Kernel] = kernelRef.get.flatMap {
    case Some(kernel) =>
      ZIO.succeed(kernel)
    case None =>
      kernelStarting.withPermit {
        kernelRef.get.flatMap {
          case Some(kernel) =>
            ZIO.succeed(kernel)
          case None =>
            val initKernel = for {
                kernel <- createKernel()
                _      <- handleKernelClosed(kernel).forkDaemon
                _      <- kernel.init().provideSomeLayer[BaseEnv with GlobalEnv](kernelFactoryEnv)
                _      <- kernelRef.set(Some(kernel))
                _      <- kernel.info() >>= publishStatus.publish1
              } yield kernel

            initKernel.tapError {
              err => kernelRef.set(None) *> status.publish1(KernelError(err))
            }
        }
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

  def kernelStatus(): TaskB[KernelBusyState] = for {
    kernelOpt <- kernelRef.get
    busyState <- kernelOpt.fold[TaskB[KernelBusyState]](ZIO.succeed(KernelBusyState(busy = false, alive = false)).absorb)(_.status())
  } yield busyState

  def cancelAll(): TaskB[Unit] = {
    for {
      kernel <- kernelRef.get.get
      _      <- kernel.cancelAll().provideSomeLayer[BaseEnv](baseLayer)
    } yield ()
  }.ignore

  def tasks(): TaskB[List[TaskInfo]] = kernelRef.get.get.flatMap {
    kernel => kernel.tasks().provideSomeLayer[BaseEnv](baseLayer)
  } orElse taskManager.list

  def subscribe(): RIO[BaseEnv with GlobalEnv with PublishMessage with UserIdentity, KernelSubscriber] = subscribing.withPermit {
    for {
      isClosed           <- closed.isDone
      _                  <- if (isClosed) ZIO.fail(PublisherClosed) else ZIO.unit
      subscriberId       <- ZIO.effectTotal(nextSubscriberId.getAndIncrement())
      subscriber         <- KernelSubscriber(subscriberId, this)
      _                  <- subscribers.put(subscriberId, subscriber)
      _                  <- subscriber.closed.await.flatMap(_ => removeSubscriber(subscriberId)).forkDaemon
      _                  <- status.publish1(PresenceUpdate(List(subscriber.presence), Nil))
      _                  <- subscriber.selections.through(status.publish).compile.drain.forkDaemon
    } yield subscriber
  }

  private def removeSubscriber(id: Int) = subscribing.withPermit {
    for {
      subscriber <- subscribers.get(id).get.mapError(_ => new NoSuchElementException(s"Subscriber $id does not exist"))
      isDone     <- subscriber.closed.isDone
      _          <- if (isDone) ZIO.unit else ZIO.fail(new IllegalStateException(s"Attempting to remove subscriber $id, which is not closed."))
      _          <- subscribers.remove(id)
      allClosed  <- subscribers.isEmpty
      kernel     <- kernelRef.get
      _          <- status.publish1(PresenceUpdate(Nil, List(id)))
      _          <- if (allClosed && kernel.isEmpty) {
        latestVersion.map(_._2.path).flatMap(path => Logging.info(s"Closing $path (idle with no more subscribers)")) *> close()
      } else ZIO.unit
    } yield ()
  }

  def close(): TaskB[Unit] =
    closed.succeed(()).unit *>
      subscribers.values.flatMap(subs => ZIO.foreachPar_(subs)(_.close())).unit *>
      kernelRef.get.flatMap(_.fold[TaskB[Unit]](ZIO.unit)(_.shutdown())) *>
      taskManager.shutdown() *>
      versionedNotebook.close()

  private def createKernel(): RIO[BaseEnv with GlobalEnv, Kernel] = kernelFactory()
    .provideSomeLayer[BaseEnv with GlobalEnv](kernelFactoryEnv)
}

object KernelPublisher {

  type GlobalVersion = Int
  type SubscriberId  = Int

  /**
    * Given the versioned notebook's current value, and a buffer of previous versioned updates, and a publisher of
    * canonical updates, rebase the given update to the latest global version, update the notebook reference, and
    * publish the rebased update.
    */
  def applyUpdate(
    versionRef: NotebookRef,
    versions: VersionBuffer[NotebookUpdate],
    publishUpdates: Publish[Task, (SubscriberId, NotebookUpdate)],
    subscriberVersions: ConcurrentHashMap[SubscriberId, (GlobalVersion, Int)])(
    subscriberId: SubscriberId,
    update: NotebookUpdate
  ): TaskG[Unit] =
    versionRef.updateAndGet(update).flatMap {
      case (nextVer, notebook) =>
        val newUpdate = update.withVersions(nextVer, update.localVersion)
        publishUpdates.publish1((subscriberId, newUpdate)) *> ZIO(versions.add(nextVer, newUpdate))
    }

  def apply(versionedRef: NotebookRef, broadcastMessage: Topic[Task, Option[Message]]): RIO[BaseEnv with GlobalEnv, KernelPublisher] = for {
    kernelFactory    <- Kernel.Factory.access
    closed           <- Promise.make[Throwable, Unit]
    // TODO: need to close if the versionedRef closes, hook up e.g. TreeRepository so it cascades
    updates          <- Queue.unbounded[Task, Option[(SubscriberId, NotebookUpdate)]]
                        // TODO: replace the following with ZTopic
    broadcastUpdates <- Topic[Task, Option[(SubscriberId, NotebookUpdate)]](None)
    broadcastStatus  <- Topic[Task, KernelStatusUpdate](KernelBusyState(busy = false, alive = false))
    broadcastResults <- Topic[Task, Option[CellResult]](None)
    taskManager      <- TaskManager(broadcastStatus)
    versionBuffer     = new VersionBuffer[NotebookUpdate]  // TODO: should NotebookRef capture this instead?
    kernelStarting   <- Semaphore.make(1)
    queueingCell     <- Semaphore.make(1)
    subscribing      <- Semaphore.make(1)
    subscribers      <- RefMap.empty[Int, KernelSubscriber]
    kernel           <- Ref.make[Option[Kernel]](None)
    subscriberVersions = new ConcurrentHashMap[SubscriberId, (GlobalVersion, Int)]()
    publisher = new KernelPublisher(
      versionedRef,
      versionBuffer,
      Publish(updates).some,
      broadcastUpdates,
      broadcastStatus,
      broadcastResults,
      broadcastMessage,
      taskManager,
      kernel,
      kernelStarting,
      queueingCell,
      subscribing,
      kernelFactory,
      closed,
      subscribers
    )
    env <- ZIO.environment[BaseEnv]
    _   <- updates.dequeue.unNoneTerminate
      .evalMap((applyUpdate(versionedRef, versionBuffer, Publish(broadcastUpdates).some, subscriberVersions) _).tupled)
      .compile.drain
      .catchAll {
        err =>
          broadcastMessage.publish1(Option(Error(0, new Exception(s"Catastrophe! An error occurred updating notebook. Editing will now be disabled.", err)))) *> broadcastMessage.publish1(None) *> publisher.close().provide(env)
      }
      .forkDaemon
  } yield publisher
}

case object PublisherClosed extends Throwable
