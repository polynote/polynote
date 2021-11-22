package polynote.server

import polynote.kernel.environment.PublishMessage
import polynote.kernel.logging.Logging
import polynote.kernel.{BaseEnv, Presence, PresenceSelection}
import polynote.messages.{CellID, KernelStatus, Notebook, NotebookUpdate, TinyString}
import polynote.runtime.CellRange
import polynote.server.KernelPublisher.{GlobalVersion, SubscriberId}
import polynote.server.auth.{Identity, IdentityProvider, Permission, UserIdentity}
import zio.stream.{SubscriptionRef, UStream, ZStream}
import zio.{Dequeue, Fiber, Promise, RIO, Task, UIO, URIO, ZIO}

import java.util.concurrent.atomic.AtomicInteger
import java.util.function.IntUnaryOperator


class KernelSubscriber private[server] (
  val id: SubscriberId,
  val identity: Option[Identity],
  currentSelection: SubscriptionRef[Option[PresenceSelection]],
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

  def close(): URIO[Logging, Unit] = closed.succeed(()).unit *> process.interrupt.unit

  def update(update: NotebookUpdate): UIO[Unit] = publisher.update(id, update)

  def notebook: Task[Notebook] = publisher.latestVersion.map(_._2)
  def currentPath: Task[String] = notebook.map(_.path)
  def checkPermission(permission: String => Permission): ZIO[SessionEnv, Throwable, Unit] =
    currentPath.map(permission) >>= IdentityProvider.checkPermission

  def setSelection(cellID: CellID, range: CellRange): UIO[Unit] =
    currentSelection.ref.set(Some(PresenceSelection(id, cellID, range)))

  def getSelection: UIO[Option[PresenceSelection]] = currentSelection.ref.get

  def selections: ZStream[Any, Throwable, PresenceSelection] = currentSelection.changes.collectSome.haltWhen(closed.await.run)

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
    broadcastUpdates: UStream[(SubscriberId, NotebookUpdate)],
    publisher: KernelPublisher
  ): RIO[BaseEnv with PublishMessage with UserIdentity, KernelSubscriber] = {

    def foreignUpdates(local: AtomicInteger, global: AtomicInteger, closed: Promise[Throwable, Unit]) =
      broadcastUpdates.haltWhen(closed)
        .tap {
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
      currentSelection <- SubscriptionRef.make[Option[PresenceSelection]](None)
      updater          <- ZStream(
          foreignUpdates(lastLocalVersion, lastGlobalVersion, closed).interruptWhen(closed.await.run),
          ZStream.fromEffect(publisher.kernelStatus().map(KernelStatus(_))) ++ publisher.subscribeStatus
            .filter(_.isRelevant(id))
            .map(update => KernelStatus(update.forSubscriber(id)))
            .interruptWhen(closed.await.run),
          ZStream.fromHub(publisher.cellResults)
            .interruptWhen(closed.await.run)
        ).flattenParUnbounded().mapM(publishMessage.publish).runDrain.forkDaemon
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