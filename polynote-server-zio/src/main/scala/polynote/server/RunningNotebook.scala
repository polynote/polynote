package polynote
package server

import java.util.concurrent.atomic.AtomicInteger

import cats.effect.concurrent.Ref
import fs2.concurrent.{Queue, SignallingRef, Topic}
import polynote.kernel.util.Publish
import Publish.{PublishEnqueue, PublishTopic}
import polynote.messages.CellID
import polynote.kernel.{BaseEnv, ClearResults, CurrentTask, Kernel, KernelBusyState, KernelConfig, KernelConfigEnv, KernelEnv, KernelEnvironment, KernelFactoryEnv, KernelStatusUpdate, KernelTaskEnvironment, Result, TaskManager}
import polynote.messages.{CellResult, Notebook, NotebookUpdate}
import polynote.server.SharedNotebook.{GlobalVersion, SubscriberId}
import polynote.util.VersionBuffer
import zio.{Fiber, Task, TaskR, ZIO}
import zio.interop.catz._

class RunningNotebook private (
  notebook: SignallingRef[Task, (GlobalVersion, Notebook)],
  updates: Queue[Task, Option[(SubscriberId, NotebookUpdate)]],
  broadcastUpdates: Topic[Task, Option[(SubscriberId, NotebookUpdate)]],
  broadcastStatus: Topic[Task, KernelStatusUpdate],
  broadcastResults: Topic[Task, CellResult],
  taskManager: TaskManager[KernelEnv],
  updater: Fiber[Throwable, Unit],
  memoizedKernel: Task[Kernel],
  kernelEnv: KernelEnv
) {

  private val publishUpdate: Publish[Task, (SubscriberId, NotebookUpdate)] = PublishEnqueue(updates).some
  private val publishStatus: Publish[Task, KernelStatusUpdate] = PublishTopic(broadcastStatus)
  private val publishResults: Publish[Task, CellResult] = PublishTopic(broadcastResults)
  private val nextSubscriberId = new AtomicInteger(0)


  def update(subscriberId: SubscriberId, update: NotebookUpdate): Task[Unit] =
    publishUpdate.publish1((subscriberId, update))

  def kernel: Task[Kernel] = memoizedKernel

  def queueCell(id: CellID): Task[Task[Unit]] = kernel.flatMap {
    kernel => kernel.queueCell(id).provide(kernelEnv).map(_.provide(kernelEnv))
  }

}

object RunningNotebook {

  /**
    * Given the versioned notebook's current value, and a buffer of previous versioned updates, and a publisher of
    * canonical updates, rebase the given update to the latest global version, update the notebook reference, and
    * publish the rebased update.
    */
  def applyUpdate(
    versionRef: Ref[Task, (GlobalVersion, Notebook)],
    versions: VersionBuffer[NotebookUpdate],
    publishUpdates: Publish[Task, (SubscriberId, NotebookUpdate)])(
    subscriberId: SubscriberId,
    update: NotebookUpdate
  ): Task[Unit] = versionRef.modify {
    case (globalVersion, notebook) =>
      val rebased = if (update.globalVersion < globalVersion) {
        versions.getRange(update.globalVersion, globalVersion).foldLeft(update) {
          case (accum, (ver, priorUpdate)) => accum.rebase(priorUpdate)
        }
      } else update
      val nextVersion = globalVersion + 1
      val result = (nextVersion, rebased.applyTo(notebook))
      (result, (subscriberId, rebased.withVersions(nextVersion, rebased.localVersion)))
  }.tap(publishUpdates.publish1).flatMap {
    case (subscriberId, update) => ZIO(versions.add(update.globalVersion, update))
  }

  def apply(notebook: Notebook, kernelFactory: kernel.Factory): TaskR[KernelConfigEnv, RunningNotebook] = for {
    versionedRef     <- SignallingRef[Task, (GlobalVersion, Notebook)]((0, notebook))
    updates          <- Queue.unbounded[Task, Option[(SubscriberId, NotebookUpdate)]]
    broadcastUpdates <- Topic[Task, Option[(SubscriberId, NotebookUpdate)]](None)
    broadcastStatus  <- Topic[Task, KernelStatusUpdate](KernelBusyState(busy = false, alive = false))
    broadcastResults <- Topic[Task, CellResult](CellResult(notebook.path, CellID(-1), ClearResults()))
    taskManager      <- TaskManager[KernelEnv](KernelTaskEnvironment.from, broadcastStatus)
    versionBuffer     = new VersionBuffer[NotebookUpdate]
    kernel           <- kernelFactory()
      .provideSomeM(ZIO.access[KernelConfigEnv](KernelConfig.from).map(_.withTaskManager(taskManager)))
      .memoize
    kernelEnv        <- ZIO.access[BaseEnv](new KernelEnvironment(_, broadcastStatus.publish1, broadcastResults.publish1))
    updater          <- updates.dequeue.unNoneTerminate
      .evalMap(applyUpdate(versionedRef, versionBuffer, PublishTopic(broadcastUpdates).some).tupled)
      .compile.drain.fork
  } yield new RunningNotebook(
    versionedRef,
    updates,
    broadcastUpdates,
    broadcastStatus,
    broadcastResults,
    taskManager,
    updater,
    kernel,
    kernelEnv
  )
}
