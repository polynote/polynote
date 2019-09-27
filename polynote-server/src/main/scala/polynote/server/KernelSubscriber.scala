package polynote.server

import java.util.concurrent.atomic.AtomicInteger

import fs2.concurrent.Topic
import fs2.Stream
import polynote.kernel.environment.PublishMessage
import polynote.kernel.{BaseEnv, GlobalEnv}
import polynote.messages.{CellID, KernelStatus, Notebook, NotebookUpdate}
import KernelPublisher.{GlobalVersion, SubscriberId}
import zio.{Fiber, Promise, Task, TaskR, ZIO}
import zio.interop.catz._


class KernelSubscriber private[server] (
  id: SubscriberId,
  closed: Promise[Throwable, Unit],
  process: Fiber[Throwable, Unit],
  val publisher: KernelPublisher,
  val lastLocalVersion: AtomicInteger,
  val lastGlobalVersion: AtomicInteger
) {

  def close(): Task[Unit] = closed.succeed(()).unit *> process.join
  def update(update: NotebookUpdate): Task[Unit] = publisher.update(id, update) *> ZIO(lastLocalVersion.set(update.localVersion)) *> ZIO(lastGlobalVersion.set(update.globalVersion))
  def notebook(): Task[Notebook] = publisher.latestVersion.map(_._2)
}

object KernelSubscriber {

  def apply(
    id: SubscriberId,
    publisher: KernelPublisher,
    globalVersion: GlobalVersion
  ): TaskR[PublishMessage, KernelSubscriber] = {
    def foreignUpdates(local: AtomicInteger, global: AtomicInteger) =
      publisher.broadcastUpdates.subscribe(128).unNone.filter(_._1 != id).map(_._2).map {
        update =>
          val knownGlobalVersion = global.get()
          if (update.globalVersion < knownGlobalVersion) {
            Some(publisher.versionBuffer.getRange(update.globalVersion, knownGlobalVersion).map(_._2).foldLeft(update)(_ rebase _).withVersions(knownGlobalVersion, local.get()))
          } else if (update.globalVersion > knownGlobalVersion) {
            Some(update.withVersions(update.globalVersion, local.get()))
          } else None
      }.unNone.evalTap(_ => ZIO(local.incrementAndGet()).unit)

    for {
      closed           <- Promise.make[Throwable, Unit]
      notebook         <- publisher.currentNotebook.get
      lastLocalVersion  = new AtomicInteger(0)
      lastGlobalVersion = new AtomicInteger(globalVersion)
      publishMessage   <- PublishMessage.access
      updater          <- Stream.emits(Seq(
          foreignUpdates(lastLocalVersion, lastGlobalVersion),
          publisher.status.subscribe(128).map(KernelStatus(notebook.path, _)),
          publisher.cellResults.subscribe(128)
        )).parJoinUnbounded.interruptWhen(closed.await.either).through(publishMessage.publish).compile.drain.fork
    } yield new KernelSubscriber(
      id,
      closed,
      updater,
      publisher,
      lastLocalVersion,
      lastGlobalVersion
    )
  }

}