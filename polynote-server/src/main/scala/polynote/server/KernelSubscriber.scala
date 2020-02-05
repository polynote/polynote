package polynote.server

import java.util.concurrent.atomic.AtomicInteger

import fs2.concurrent.{SignallingRef, Topic}
import fs2.Stream
import polynote.kernel.environment.{Config, PublishMessage}
import polynote.kernel.{BaseEnv, GlobalEnv, Presence, PresenceSelection, StreamUIOps, StreamThrowableOps}
import polynote.messages.{CellID, KernelStatus, Notebook, NotebookUpdate, TinyString}
import KernelPublisher.{GlobalVersion, SubscriberId}
import polynote.server.auth.{Identity, IdentityProvider, Permission, UserIdentity}
import zio.{Fiber, Promise, RIO, Task, UIO, ZIO}
import zio.interop.catz._


class KernelSubscriber private[server] (
  val id: SubscriberId,
  val identity: Option[Identity],
  currentSelection: SignallingRef[UIO, Option[PresenceSelection]],
  val closed: Promise[Throwable, Unit],
  process: Fiber[Throwable, Unit],
  val publisher: KernelPublisher,
  val lastLocalVersion: AtomicInteger,
  val lastGlobalVersion: AtomicInteger
) {
  lazy val presence: Presence = {
    val (name, avatar) = identity match {
      case Some(identity) => (identity.name, identity.avatar)
      case None           => (s"Anonymous ($id)", None)
    }
    Presence(id, name, avatar.map(TinyString.apply))
  }

  def close(): UIO[Unit] = closed.succeed(()).unit *> process.interrupt.unit
  def update(update: NotebookUpdate): Task[Unit] = publisher.update(id, update) *> ZIO(lastLocalVersion.set(update.localVersion)) *> ZIO(lastGlobalVersion.set(update.globalVersion))
  def notebook: Task[Notebook] = publisher.latestVersion.map(_._2)
  def currentPath: Task[String] = notebook.map(_.path)
  def checkPermission(permission: String => Permission): ZIO[SessionEnv, Throwable, Unit] =
    currentPath.map(permission) >>= IdentityProvider.checkPermission

  def setSelection(cellID: CellID, range: (Int, Int)): UIO[Unit] =
    currentSelection.set(Some(PresenceSelection(id, cellID, range)))

  def getSelection: UIO[Option[PresenceSelection]] = currentSelection.get

  def selections: Stream[UIO, PresenceSelection] = currentSelection.discrete.unNone.interruptAndIgnoreWhen(closed)
}

object KernelSubscriber {

  def apply(
    id: SubscriberId,
    publisher: KernelPublisher
  ): RIO[PublishMessage with UserIdentity, KernelSubscriber] = {

    def rebaseUpdate(update: NotebookUpdate, globalVersion: GlobalVersion, localVersion: Int) =
      publisher.versionBuffer.getRange(update.globalVersion, globalVersion)
        .foldLeft(update)(_ rebase _)
        .withVersions(globalVersion, localVersion)

    def foreignUpdates(local: AtomicInteger, global: AtomicInteger) =
      publisher.broadcastUpdates.subscribe(128).unNone.filter(_._1 != id).map(_._2).map {
        update =>
          val knownGlobalVersion = global.get()
          if (update.globalVersion < knownGlobalVersion) {
            Some(rebaseUpdate(update, knownGlobalVersion, local.get()))
          } else if (update.globalVersion > knownGlobalVersion) {
            Some(update.withVersions(update.globalVersion, local.get()))
          } else None
      }.unNone.evalTap(_ => ZIO(local.incrementAndGet()).unit)

    for {
      closed           <- Promise.make[Throwable, Unit]
      identity         <- UserIdentity.access
      versioned        <- publisher.versionedNotebook.get
      (ver, notebook)   = versioned
      lastLocalVersion  = new AtomicInteger(0)
      lastGlobalVersion = new AtomicInteger(ver)
      publishMessage   <- PublishMessage.access
      currentSelection <- SignallingRef[UIO, Option[PresenceSelection]](None)
      updater          <- Stream.emits(Seq(
          foreignUpdates(lastLocalVersion, lastGlobalVersion),
          publisher.status.subscribe(128).tail.filter(_.isRelevant(id)).map(update => KernelStatus(update.forSubscriber(id))),
          publisher.cellResults.subscribe(128).tail.unNone
        )).parJoinUnbounded.interruptAndIgnoreWhen(closed).through(publishMessage.publish).compile.drain.fork
    } yield new KernelSubscriber(
      id,
      identity,
      currentSelection,
      closed,
      updater,
      publisher,
      lastLocalVersion,
      lastGlobalVersion
    )
  }

}