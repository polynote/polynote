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
import polynote.kernel.environment.{Config, CurrentNotebook, Env, PublishMessage, PublishResult, PublishStatus}
import polynote.kernel.interpreter.Interpreter
import polynote.messages.{CellID, CellResult, KernelStatus, Message, Notebook, NotebookUpdate, ShortString}
import polynote.kernel.{BaseEnv, CellEnv, CellEnvT, ClearResults, Completion, GlobalEnv, Kernel, KernelBusyState, KernelStatusUpdate, Result, ScalaCompiler, Signatures, TaskManager}
import polynote.server.SharedNotebook.{GlobalVersion, SubscriberId}
import polynote.util.VersionBuffer
import zio.{Fiber, Promise, Semaphore, Task, TaskR, UIO, ZIO}
import zio.interop.catz._

class KernelPublisher private (
  val currentNotebook: Ref[Task, Notebook],
  versionedNotebook: SignallingRef[Task, (GlobalVersion, Notebook)],
  val versionBuffer: VersionBuffer[NotebookUpdate],
  publishUpdate: Publish[Task, (SubscriberId, NotebookUpdate)],
  val broadcastUpdates: Topic[Task, Option[(SubscriberId, NotebookUpdate)]],
  val status: Topic[Task, KernelStatusUpdate],
  val cellResults: Topic[Task, CellResult],
  val taskManager: TaskManager.Service,
  updater: Fiber[Throwable, Unit],
  kernelRef: Ref[Task, Option[Kernel]],
  kernelStarting: Semaphore,
  kernelFactory: Kernel.Factory.Service,
  closed: Promise[Throwable, Unit]
) {

  private val localNotebookEnv = CurrentNotebook.of(currentNotebook)

  val publishStatus: Publish[Task, KernelStatusUpdate] = status

  private case class LocalCellEnv(notebookPath: ShortString, cellID: CellID) extends CellEnvT {
    override val currentNotebook: Ref[Task, Notebook] = KernelPublisher.this.currentNotebook
    override val taskManager: TaskManager.Service = KernelPublisher.this.taskManager
    override val publishStatus: Publish[Task, KernelStatusUpdate] = KernelPublisher.this.publishStatus
    override val publishResult: Publish[Task, Result] = cellResults.imap(_.result)(CellResult(notebookPath, cellID, _))
  }

  private def cellEnv(cellID: Int): Task[CellEnv] =
    currentNotebook.get.map(nb => LocalCellEnv(nb.path, CellID(cellID)))

  private val nextSubscriberId = new AtomicInteger(0)

  def notebooks: Stream[Task, Notebook] = versionedNotebook.discrete.map(_._2).interruptWhen(closed.await.either)
  def latestVersion: Task[(GlobalVersion, Notebook)] = versionedNotebook.get
  def update(subscriberId: SubscriberId, update: NotebookUpdate): Task[Unit] =
    publishUpdate.publish1((subscriberId, update))

  def kernel: TaskR[BaseEnv with GlobalEnv, Kernel] = kernelRef.get.flatMap {
    case Some(kernel) => ZIO.succeed(kernel)
    case None => kernelStarting.withPermit {
      kernelRef.get.flatMap {
        case Some(kernel) => ZIO.succeed(kernel)
        case None => for {
          kernel <- createKernel()
          _      <- kernel.init().provideSomeM(Env.enrichM[BaseEnv with GlobalEnv](cellEnv(-1)))
          _      <- kernelRef.set(Some(kernel))
        } yield kernel
      }
    }
  }

  def killKernel(): TaskR[BaseEnv with GlobalEnv, Unit] = kernelRef.get.flatMap {
    case None => ZIO.unit
    case Some(kernel) => kernelStarting.withPermit(kernel.shutdown() *> kernelRef.set(None))
  }

  def restartKernel(forceStart: Boolean): TaskR[BaseEnv with GlobalEnv, Unit] = kernelRef.get.flatMap {
    case None if forceStart => kernel.unit
    case None => ZIO.unit
    case Some(_) => killKernel() *> this.kernel.unit
  }

  def queueCell(cellID: CellID): TaskR[BaseEnv with GlobalEnv, Task[Unit]] = for {
    notebook <- versionedNotebook.get
    env       = LocalCellEnv(notebook._2.path, cellID)
    kernel   <- kernel
    result   <- kernel.queueCell(cellID).provideSomeM(Env.enrich[BaseEnv with GlobalEnv](env: CellEnv))
  } yield result

  def completionsAt(cellID: CellID, pos: Int): TaskR[BaseEnv with GlobalEnv, List[Completion]] = for {
    kernel      <- kernel
    completions <- kernel.completionsAt(cellID, pos).provide(localNotebookEnv)
  } yield completions

  def parametersAt(cellID: CellID, pos: Int): TaskR[BaseEnv with GlobalEnv, Option[Signatures]] = for {
    kernel      <- kernel
    signatures  <- kernel.parametersAt(cellID, pos).provide(localNotebookEnv)
  } yield signatures

  def kernelStatus(): Task[KernelBusyState] = for {
    kernelOpt <- kernelRef.get
    busyState <- kernelOpt.fold(ZIO.succeed(KernelBusyState(busy = false, alive = false)).absorb)(_.status())
  } yield busyState

  def cancelAll(): Task[Unit] = taskManager.cancelAll()

  def subscribe(): TaskR[BaseEnv with GlobalEnv with PublishMessage, KernelSubscriber] = for {
    versioned      <- versionedNotebook.get
    (version, notebook) = versioned
    subscriberId   <- ZIO.effectTotal(nextSubscriberId.getAndIncrement())
    subscriber <- KernelSubscriber(subscriberId, this, version)
  } yield subscriber

  def close(): Task[Unit] = closed.succeed(()).const(())

  private def createKernel(): TaskR[BaseEnv with GlobalEnv, Kernel] = kernelFactory()
    .provideSomeM(Env.enrichM[BaseEnv with GlobalEnv](cellEnv(-1)))
}

object KernelPublisher {

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

  def apply(notebook: Notebook): TaskR[BaseEnv with GlobalEnv, KernelPublisher] = for {
    kernelFactory    <- Kernel.Factory.access
    versionedRef     <- SignallingRef[Task, (GlobalVersion, Notebook)]((0, notebook))
    closed           <- Promise.make[Throwable, Unit]
    currentNotebook   = new UnversionedRef(versionedRef)
    updates          <- Queue.unbounded[Task, Option[(SubscriberId, NotebookUpdate)]]
    broadcastUpdates <- Topic[Task, Option[(SubscriberId, NotebookUpdate)]](None)
    broadcastStatus  <- Topic[Task, KernelStatusUpdate](KernelBusyState(busy = false, alive = false))
    broadcastResults <- Topic[Task, CellResult](CellResult(notebook.path, CellID(-1), ClearResults()))
    taskManager      <- TaskManager(broadcastStatus)
    versionBuffer     = new VersionBuffer[NotebookUpdate]
    kernelStarting   <- Semaphore.make(1)
    kernel           <- Ref[Task].of[Option[Kernel]](None)
    subscriberVersions = new ConcurrentHashMap[SubscriberId, (GlobalVersion, Int)]()
    updater          <- updates.dequeue.unNoneTerminate
      .evalMap((applyUpdate(versionedRef, versionBuffer, Publish(broadcastUpdates).some, subscriberVersions) _).tupled)
      .compile.drain.fork
  } yield new KernelPublisher(
    currentNotebook,
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
    kernelFactory,
    closed
  )
}
