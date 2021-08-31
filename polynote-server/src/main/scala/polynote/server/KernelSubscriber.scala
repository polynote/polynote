package polynote.server

import java.util.concurrent.atomic.AtomicInteger
import fs2.concurrent.{SignallingRef, Topic}
import fs2.Stream
import polynote.kernel.environment.{Config, PublishMessage}
import polynote.kernel.{BaseEnv, GlobalEnv, Presence, PresenceSelection, StreamThrowableOps, StreamUIOps}
import polynote.messages.{CellID, CreateComment, DeleteCell, DeleteComment, InsertCell, KernelStatus, Notebook, NotebookUpdate, TinyString, UpdateComment}
import KernelPublisher.{GlobalVersion, SubscriberId}
import polynote.kernel.logging.Logging
import polynote.runtime.CellRange
import polynote.server.auth.{Identity, IdentityProvider, Permission, UserIdentity}
import polynote.util.VersionBuffer
import zio.{Fiber, Promise, RIO, Task, UIO, URIO, ZIO}
import zio.interop.catz._

import java.util.function.IntUnaryOperator


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

  def update(update: NotebookUpdate): Task[Unit] = publisher.update(id, update)

  def notebook: Task[Notebook] = publisher.latestVersion.map(_._2)
  def currentPath: Task[String] = notebook.map(_.path)
  def checkPermission(permission: String => Permission): ZIO[SessionEnv, Throwable, Unit] =
    currentPath.map(permission) >>= IdentityProvider.checkPermission

  def setSelection(cellID: CellID, range: CellRange): UIO[Unit] =
    currentSelection.set(Some(PresenceSelection(id, cellID, range)))

  def getSelection: UIO[Option[PresenceSelection]] = currentSelection.get

  def selections: Stream[UIO, PresenceSelection] = currentSelection.discrete.unNone.interruptAndIgnoreWhen(closed)

  private[server] val getLastGlobalVersion = ZIO.effectTotal(lastGlobalVersion.get())
  private[server] def setLastGlobalVersion(version: GlobalVersion): UIO[Unit] = ZIO.effectTotal(lastGlobalVersion.set(version))
  private[server] def updateLastGlobalVersion(version: GlobalVersion): UIO[Unit] = ZIO.effectTotal {
    lastGlobalVersion.updateAndGet(new IntUnaryOperator {
      override def applyAsInt(operand: GlobalVersion): GlobalVersion = math.max(version, operand)
    })
  }
}

object KernelSubscriber {

  def apply(
    id: SubscriberId,
    publisher: KernelPublisher
  ): RIO[PublishMessage with UserIdentity, KernelSubscriber] = {

    def foreignUpdates(local: AtomicInteger, global: AtomicInteger) =
      publisher.broadcastUpdates.subscribe(128).unNone
        .evalTap {
          case (`id`, update) => ZIO.effectTotal { local.set(update.localVersion) }
          case _              => ZIO.unit
        }.filter {
          case (subscriberId, update) => subscriberId != id || update.echoOriginatingSubscriber
        }.map {
          case (_, update) => update.withVersions(update.globalVersion, local.get())
        }

    for {
      closed           <- Promise.make[Throwable, Unit]
      identity         <- UserIdentity.access
      versioned        <- publisher.versionedNotebook.getVersioned
      (ver, notebook)   = versioned
      lastLocalVersion  = new AtomicInteger(0)
      lastGlobalVersion = new AtomicInteger(ver)
      publishMessage   <- PublishMessage.access
      currentSelection <- SignallingRef[UIO, Option[PresenceSelection]](None)
      updater          <- Stream.emits(Seq(
          foreignUpdates(lastLocalVersion, lastGlobalVersion),
          publisher.status.subscribe(128).tail.filter(_.isRelevant(id)).map(update => KernelStatus(update.forSubscriber(id))),
          publisher.cellResults.subscribe(128).tail.unNone
        )).parJoinUnbounded.interruptAndIgnoreWhen(closed).through(publishMessage.publish).compile.drain.forkDaemon
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