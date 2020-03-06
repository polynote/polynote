package polynote
package server

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import cats.effect.concurrent.Ref
import cats.instances.list._
import cats.syntax.traverse._
import fs2.Stream
import fs2.concurrent.{Queue, SignallingRef, Topic}
import polynote.kernel.util.{Publish, RefMap}
import Publish.{PublishEnqueue, PublishTopic}
import polynote.config.PolynoteConfig
import polynote.env.ops._
import polynote.kernel.environment.{Config, CurrentNotebook, Env, NotebookUpdates, PublishMessage, PublishResult, PublishStatus}
import polynote.kernel.interpreter.Interpreter
import polynote.messages.{CellID, CellResult, Error, KernelStatus, Message, Notebook, NotebookUpdate, RenameNotebook, ShortList, ShortString}
import polynote.kernel.{BaseEnv, CellEnv, ClearResults, Completion, Deque, ExecutionInfo, GlobalEnv, Kernel, KernelBusyState, KernelError, KernelStatusUpdate, Output, Presence, PresenceSelection, PresenceUpdate, Result, ScalaCompiler, Signatures, StreamThrowableOps, TaskB, TaskManager}
import polynote.util.VersionBuffer
import zio.{Fiber, Has, Promise, RIO, Semaphore, Task, UIO, ZIO, ZLayer}
import KernelPublisher.{GlobalVersion, SubscriberId}
import polynote.kernel.logging.Logging
import polynote.kernel.task.TaskManager
import polynote.server.auth.UserIdentity

import scala.concurrent.duration.FiniteDuration

class KernelPublisher private (
  val versionedNotebook: SignallingRef[Task, (GlobalVersion, Notebook)],
  val versionBuffer: VersionBuffer[NotebookUpdate],
  publishUpdate: Publish[Task, (SubscriberId, NotebookUpdate)],
  val broadcastUpdates: Topic[Task, Option[(SubscriberId, NotebookUpdate)]],
  val status: Topic[Task, KernelStatusUpdate],
  val cellResults: Topic[Task, Option[CellResult]],
  val broadcastAll: Topic[Task, Option[Message]],
  val taskManager: TaskManager.Service,
  kernelRef: Ref[Task, Option[Kernel]],
  kernelStarting: Semaphore,
  queueingCell: Semaphore,
  subscribing: Semaphore,
  kernelFactory: Kernel.Factory.Service,
  val closed: Promise[Throwable, Unit],
  subscribers: RefMap[Int, KernelSubscriber]
) {
  val publishStatus: Publish[Task, KernelStatusUpdate] = status

  private val baseLayer: ZLayer[Any, Nothing, CurrentNotebook with TaskManager with PublishStatus] =
    ZLayer.succeedMany(Has(versionedNotebook: Ref[Task, (GlobalVersion, Notebook)]) ++ Has(taskManager) ++ Has(publishStatus))

  private def cellLayer(cellID: CellID, tapResults: Option[Result => Task[Unit]] = None): ZLayer[Any, Nothing, PublishResult] =
    ZLayer.succeed {
      val publish = Publish(cellResults).contramap[Result](result => Some(CellResult(cellID, result)))
      tapResults.fold(publish)(fn => publish.tap(fn))
    }

  private def cellEnv(cellID: CellID, tapResults: Option[Result => Task[Unit]] = None): ZLayer[Any, Nothing, CellEnv] =
    baseLayer ++ cellLayer(cellID, tapResults)

  private val kernelFactoryEnv: ZLayer[Any, Nothing, CellEnv with NotebookUpdates] =
    cellEnv(CellID(-1)) ++ ZLayer.succeed(broadcastUpdates.subscribe(128).unNone.map(_._2))

  private val nextSubscriberId = new AtomicInteger(0)

  /**
    * @return A stream of all discrete versions of the notebook, even if the version number isn't updated. The version
    *         number is updated only after a content change, while this stream will also contain a notebook for other
    *         changes (such as results). See [[SignallingRef.discrete]] for the semantics of "discrete".
    */
  def notebooks: Stream[Task, Notebook] = versionedNotebook.discrete.map(_._2).interruptAndIgnoreWhen(closed)

  /**
    * @param duration The minimum delay between notebook versions
    * @return A stream of notebook versions, which returns no more than one version per specified duration. When the
    *         notebook is closed, emits one final version with no delay.
    */
  def notebooksTimed(duration: FiniteDuration): Stream[Task, Notebook] = {
    import zio.interop.catz.implicits.ioTimer
    (Stream.eval(versionedNotebook.get) ++ Stream.fixedDelay[UIO](duration).zipRight(versionedNotebook.continuous)).filterWithPrevious {
      (previous, current) => !(previous eq current) && previous != current
    }.map(_._2).interruptAndIgnoreWhen(closed) ++ Stream.eval(versionedNotebook.get.map(_._2))
  }

  def latestVersion: Task[(GlobalVersion, Notebook)] = versionedNotebook.get

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
    kernel.awaitClosed.catchAll {
      err => publishStatus.publish1(KernelError(err)) *> Logging.error(s"Kernel closed with error", err)
    } *> Logging.info("Kernel closed") *> kernelRef.set(None) *> publishStatus.publish1(KernelBusyState(busy = false, alive = false))

  def kernel: RIO[BaseEnv with GlobalEnv, Kernel] = kernelRef.get.flatMap {
    case Some(kernel) => ZIO.succeed(kernel)
    case None => kernelStarting.withPermit {
      kernelRef.get.flatMap {
        case Some(kernel) => ZIO.succeed(kernel)
        case None =>
          val initKernel = for {
              kernel <- createKernel()
              _      <- handleKernelClosed(kernel).forkDaemon
              _      <- kernel.init().provideSomeLayer[BaseEnv with GlobalEnv](kernelFactoryEnv)
              _      <- kernelRef.set(Some(kernel))
              _      <- kernel.info() >>= publishStatus.publish1
            } yield kernel

          initKernel.tapError {
            err => kernelRef.get.flatMap(_.map(_.shutdown()).getOrElse(ZIO.unit)) *> kernelRef.set(None) *> status.publish1(KernelError(err))
          }
      }
    }
  }

  def killKernel(): RIO[BaseEnv with GlobalEnv, Unit] = kernelRef.get.flatMap {
    case None => ZIO.unit
    case Some(kernel) => kernelStarting.withPermit(kernel.shutdown() *> kernelRef.set(None))
  }

  def restartKernel(forceStart: Boolean): RIO[BaseEnv with GlobalEnv, Unit] = kernelRef.get.flatMap {
    case None if forceStart => kernel.unit
    case None => ZIO.unit
    case Some(_) => killKernel() *> this.kernel.unit
  }


  // process carriage returns in the string
  private def collapseCrs(str: String): String = str.replaceAll("\\r\\n", "\n").replaceAll("[^\\n]*\\r", "")

  def queueCell(cellID: CellID): RIO[BaseEnv with GlobalEnv, Task[Unit]] = queueingCell.withPermit {

    def writeResult(result: Result) = versionedNotebook.update {
      case (ver, nb) => ver -> nb.updateCell(cellID) {
        cell => result match {
          case ClearResults() => cell.copy(results = ShortList(Nil))
          case execInfo@ExecutionInfo(_, _) => cell.copy(results = ShortList(cell.results :+ execInfo), metadata = cell.metadata.copy(executionInfo = Some(execInfo)))
          case Output(contentType, str) =>
            val updatedResults = cell.results.lastOption match {
              case Some(Output(`contentType`, str1)) => cell.results.dropRight(1) :+ Output(contentType, collapseCrs(str1 + str))
              case _ => cell.results :+ result
            }
            cell.copy(results = ShortList.fromRight(updatedResults))
          case result => cell.copy(results = ShortList.fromRight(cell.results :+ result))
        }
      }
    }

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
      kernel <- kernelRef.get.orDie.get
      _      <- kernel.cancelAll().provideSomeLayer[BaseEnv](baseLayer)
    } yield ()
  }.ignore

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

  private def removeSubscriber(id: Int) = for {
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

  def rename(newPath: String): Task[Unit] = for {
    oldPath <- versionedNotebook.get.map(_._2.path)
    _       <- versionedNotebook.update(vn => vn._1 -> vn._2.copy(path = newPath))
  } yield ()

  def close(): TaskB[Unit] =
    closed.succeed(()).unit *>
      subscribers.values.flatMap(_.map(_.close()).sequence).unit *>
      kernelRef.get.flatMap(_.fold[TaskB[Unit]](ZIO.unit)(_.shutdown())) *>
      taskManager.shutdown()

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
    versionRef: Ref[Task, (GlobalVersion, Notebook)],
    versions: VersionBuffer[NotebookUpdate],
    publishUpdates: Publish[Task, (SubscriberId, NotebookUpdate)],
    subscriberVersions: ConcurrentHashMap[SubscriberId, (GlobalVersion, Int)])(
    subscriberId: SubscriberId,
    update: NotebookUpdate
  ): TaskB[Unit] = {
    versionRef.access.flatMap {
      case ((globalVersion, notebook), setter) =>
        ZIO(update.applyTo(notebook))
          .map((globalVersion + 1, _))
          .flatMap {
            res =>
              setter(res).flatMap {
                success =>
                  if (success) {
                    val newUpdate = update.withVersions(res._1, update.localVersion)
                    publishUpdates.publish1((subscriberId, newUpdate)) *> ZIO(versions.add(newUpdate.globalVersion, newUpdate))
                  } else {
                    // there was a concurrent modification to this ref, so try to access again
                    Logging.warn("Retrying NotebookUpdate because concurrent access was detected") *> applyUpdate(versionRef, versions, publishUpdates, subscriberVersions)(subscriberId, update)
                  }
              }

          }
    }
  }

  def apply(notebook: Notebook, broadcastMessage: Topic[Task, Option[Message]]): RIO[BaseEnv with GlobalEnv, KernelPublisher] = for {
    kernelFactory    <- Kernel.Factory.access
    versionedRef     <- SignallingRef[Task, (GlobalVersion, Notebook)]((0, notebook))
    closed           <- Promise.make[Throwable, Unit]
    updates          <- Queue.unbounded[Task, Option[(SubscriberId, NotebookUpdate)]]
    broadcastUpdates <- Topic[Task, Option[(SubscriberId, NotebookUpdate)]](None)
    broadcastStatus  <- Topic[Task, KernelStatusUpdate](KernelBusyState(busy = false, alive = false))
    broadcastResults <- Topic[Task, Option[CellResult]](None)
    taskManager      <- TaskManager(broadcastStatus)
    versionBuffer     = new VersionBuffer[NotebookUpdate]
    kernelStarting   <- Semaphore.make(1)
    queueingCell     <- Semaphore.make(1)
    subscribing      <- Semaphore.make(1)
    subscribers      <- RefMap.empty[Int, KernelSubscriber]
    kernel           <- Ref[Task].of[Option[Kernel]](None)
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
    _  <- updates.dequeue.unNoneTerminate
      .evalMap((applyUpdate(versionedRef, versionBuffer, Publish(broadcastUpdates).some, subscriberVersions) _).tupled)
      .compile.drain
      .catchAll {
        err =>
          broadcastMessage.publish1(Option(Error(0, new Exception(s"Catastrophe! An error occurred updating notebook at ${notebook.path}. Editing will now be disabled.", err)))) *> broadcastMessage.publish1(None) *> publisher.close().provide(env)
      }
      .forkDaemon
  } yield publisher
}

case object PublisherClosed extends Throwable
