package polynote
package server

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import cats.effect.concurrent.Ref
import fs2.Stream
import fs2.concurrent.{Queue, SignallingRef, Topic}
import polynote.kernel.util.Publish
import Publish.{PublishEnqueue, PublishTopic}
import polynote.config.PolynoteConfig
import polynote.env.ops._
import polynote.kernel.environment.{Config, CurrentNotebook, Env, NotebookUpdates, PublishMessage, PublishResult, PublishStatus}
import polynote.kernel.interpreter.Interpreter
import polynote.messages.{CellID, CellResult, KernelStatus, Message, Notebook, NotebookUpdate, ShortList, ShortString}
import polynote.kernel.{BaseEnv, CellEnv, CellEnvT, ClearResults, Completion, Deque, ExecutionInfo, GlobalEnv, Kernel, KernelBusyState, KernelStatusUpdate, Result, ScalaCompiler, Signatures, TaskB, TaskManager}
import polynote.util.VersionBuffer
import zio.{Fiber, Promise, Semaphore, Task, RIO, UIO, ZIO}
import zio.interop.catz._
import KernelPublisher.{GlobalVersion, SubscriberId}

import scala.concurrent.duration.FiniteDuration

class KernelPublisher private (
  val versionedNotebook: SignallingRef[Task, (GlobalVersion, Notebook)],
  val versionBuffer: VersionBuffer[NotebookUpdate],
  publishUpdate: Publish[Task, (SubscriberId, NotebookUpdate)],
  val broadcastUpdates: Topic[Task, Option[(SubscriberId, NotebookUpdate)]],
  val status: Topic[Task, KernelStatusUpdate],
  val cellResults: Topic[Task, Option[CellResult]],
  val taskManager: TaskManager.Service,
  updater: Fiber[Throwable, Unit],
  kernelRef: Ref[Task, Option[Kernel]],
  kernelStarting: Semaphore,
  queueingCell: Semaphore,
  kernelFactory: Kernel.Factory.Service,
  val closed: Promise[Throwable, Unit]
) {
  val publishStatus: Publish[Task, KernelStatusUpdate] = status

  private case class LocalCellEnv(notebookPath: ShortString, cellID: CellID, tapResults: Option[Result => Task[Unit]] = None) extends CellEnvT with NotebookUpdates {
    override val currentNotebook: Ref[Task, (GlobalVersion, Notebook)] = versionedNotebook
    override val taskManager: TaskManager.Service = KernelPublisher.this.taskManager
    override val publishStatus: Publish[Task, KernelStatusUpdate] = KernelPublisher.this.publishStatus
    override val publishResult: Publish[Task, Result] = {
      val publish = Publish(cellResults).contramap[Result](result => Some(CellResult(notebookPath, cellID, result)))
      tapResults.fold(publish)(fn => publish.tap(fn))
    }
    override lazy val notebookUpdates: Stream[Task, NotebookUpdate] = broadcastUpdates.subscribe(128).unNone.map(_._2)
  }

  private def cellEnv(cellID: Int, tapResults: Option[Result => Task[Unit]] = None): Task[CellEnv] =
    versionedNotebook.get.map {
      case (_, nb) => LocalCellEnv(nb.path, CellID(cellID), tapResults)
    }

  private def kernelFactoryEnv: Task[CellEnv with NotebookUpdates] = versionedNotebook.get.map {
    case (_, nb) => LocalCellEnv(nb.path, CellID(-1))
  }

  private val nextSubscriberId = new AtomicInteger(0)

  /**
    * @return A stream of all discrete versions of the notebook, even if the version number isn't updated. The version
    *         number is updated only after a content change, while this stream will also contain a notebook for other
    *         changes (such as results). See [[SignallingRef.discrete]] for the semantics of "discrete".
    */
  def notebooks: Stream[Task, Notebook] = versionedNotebook.discrete.map(_._2).interruptWhen(closed.await.either)

  /**
    * @param duration The minimum delay between notebook versions
    * @return A stream of notebook versions, which returns no more than one version per specified duration. When the
    *         notebook is closed, emits one final version with no delay.
    */
  def notebooksTimed(duration: FiniteDuration): Stream[Task, Notebook] = {
    import zio.interop.catz.implicits.ioTimer
    (Stream.eval(versionedNotebook.get) ++ Stream.fixedDelay[UIO](duration).zipRight(versionedNotebook.continuous)).filterWithPrevious {
      (previous, current) => !(previous eq current) && previous != current
    }.map(_._2).interruptWhen(closed.await.either) ++ Stream.eval(versionedNotebook.get.map(_._2))
  }

  def latestVersion: Task[(GlobalVersion, Notebook)] = versionedNotebook.get

  def update(subscriberId: SubscriberId, update: NotebookUpdate): Task[Unit] =
    publishUpdate.publish1((subscriberId, update))

  def kernel: RIO[BaseEnv with GlobalEnv, Kernel] = kernelRef.get.flatMap {
    case Some(kernel) => ZIO.succeed(kernel)
    case None => kernelStarting.withPermit {
      kernelRef.get.flatMap {
        case Some(kernel) => ZIO.succeed(kernel)
        case None =>
          val initKernel = for {
              kernel <- createKernel()
              _      <- kernel.init().provideSomeM(Env.enrichM[BaseEnv with GlobalEnv](cellEnv(-1)))
              _      <- kernelRef.set(Some(kernel))
              _      <- kernel.info() >>= publishStatus.publish1
            } yield kernel

          initKernel.tapError {
            err => closed.succeed(())
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

  def queueCell(cellID: CellID): RIO[BaseEnv with GlobalEnv, Task[Unit]] = queueingCell.withPermit {
    def writeResult(result: Result) = versionedNotebook.update {
      case (ver, nb) => ver -> nb.updateCell(cellID) {
        cell => result match {
          case ClearResults() => cell.copy(results = ShortList(Nil))
          case execInfo@ExecutionInfo(_, _) => cell.copy(results = ShortList(cell.results :+ execInfo), metadata = cell.metadata.copy(executionInfo = Some(execInfo)))
          case result => cell.copy(results = ShortList(cell.results :+ result))
        }
      }
    }

    for {
      env      <- cellEnv(cellID, tapResults = Some(writeResult))
      kernel   <- kernel
      result   <- kernel.queueCell(cellID).provideSomeM(Env.enrich[BaseEnv with GlobalEnv](env))
    } yield result
  }

  def completionsAt(cellID: CellID, pos: Int): RIO[BaseEnv with GlobalEnv, List[Completion]] = for {
    env         <- cellEnv(cellID)
    kernel      <- kernel
    completions <- kernel.completionsAt(cellID, pos).provideSomeM(Env.enrich[BaseEnv with GlobalEnv](env))
  } yield completions

  def parametersAt(cellID: CellID, pos: Int): RIO[BaseEnv with GlobalEnv, Option[Signatures]] = for {
    env         <- cellEnv(cellID)
    kernel      <- kernel
    signatures  <- kernel.parametersAt(cellID, pos).provideSomeM(Env.enrich[BaseEnv with GlobalEnv](env))
  } yield signatures

  def kernelStatus(): TaskB[KernelBusyState] = for {
    kernelOpt <- kernelRef.get
    busyState <- kernelOpt.fold[TaskB[KernelBusyState]](ZIO.succeed(KernelBusyState(busy = false, alive = false)).absorb)(_.status())
  } yield busyState

  def cancelAll(): TaskB[Unit] = {
    for {
      kernel <- kernelRef.get.orDie.get
      _      <- kernel.cancelAll().provideSomeM(Env.enrich[BaseEnv](TaskManager.of(taskManager)))
    } yield ()
  }.ignore

  def subscribe(): RIO[BaseEnv with GlobalEnv with PublishMessage, KernelSubscriber] = for {
    subscriberId       <- ZIO.effectTotal(nextSubscriberId.getAndIncrement())
    subscriber         <- KernelSubscriber(subscriberId, this)
  } yield subscriber

  def close(): Task[Unit] = closed.succeed(()).as(()) *> taskManager.shutdown()

  private def createKernel(): RIO[BaseEnv with GlobalEnv, Kernel] = kernelFactory()
    .provideSomeM(Env.enrichM[BaseEnv with GlobalEnv](kernelFactoryEnv))
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
  ): Task[Unit] = versionRef.modify {
    case (globalVersion, notebook) =>
      val nextVersion = globalVersion + 1
      val result = (nextVersion, update.applyTo(notebook))
      (result, (subscriberId, update.withVersions(nextVersion, update.localVersion)))
  }.tap(publishUpdates.publish1).flatMap {
    case (subscriberId, update) => ZIO(versions.add(update.globalVersion, update))
  }

  def apply(notebook: Notebook): RIO[BaseEnv with GlobalEnv, KernelPublisher] = for {
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
    kernel           <- Ref[Task].of[Option[Kernel]](None)
    subscriberVersions = new ConcurrentHashMap[SubscriberId, (GlobalVersion, Int)]()
    updater          <- updates.dequeue.unNoneTerminate
      .evalMap((applyUpdate(versionedRef, versionBuffer, Publish(broadcastUpdates).some, subscriberVersions) _).tupled)
      .compile.drain.fork
  } yield new KernelPublisher(
    versionedRef,
    versionBuffer,
    Publish(updates).some,
    broadcastUpdates,
    broadcastStatus,
    broadcastResults,
    taskManager,
    updater,
    kernel,
    kernelStarting,
    queueingCell,
    kernelFactory,
    closed
  )
}
