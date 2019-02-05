package polynote.server

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

import cats.Monad
import cats.effect.concurrent.{Deferred, Ref, Semaphore}
import cats.effect.{Concurrent, ContextShift, IO}
import cats.syntax.functor._
import cats.syntax.flatMap._
import fs2.Stream
import fs2.concurrent.{Enqueue, Queue, SignallingRef, Topic}
import polynote.kernel._
import polynote.kernel.util.{Publish, ReadySignal, WindowBuffer}
import polynote.messages._
import polynote.server.IOSharedNotebook.{GlobalVersion, SubscriberId}
import polynote.util.VersionBuffer

/**
  * SharedNotebook is responsible for managing concurrent access and updates to a notebook. It's the central authority
  * for the canonical serialization of edits to the notebook. Eventually, it should resolve conflicts in concurrent edits
  * and broadcast changes to subscribers.
  *
  * Here's the current idea for how this would work (though it's not completely implemented):
  *
  * The notebook has a "global" (server) version number, and each client has a "local" (client) version number. When
  * a client sends an edit to the server, it includes the latest global version it knows about, and its local version,
  * which it then increments. When the server broadcasts an edit, it sends a message to each subscriber containing
  * the global version of the edit, and the local version to which it applies for that client. Then, the client can
  * transform the edit (if necessary) to its current local version, and it will be in a consistent state with the
  * global version.
  *
  * Similarly, the server can do part of the transformation work – when it receives an edit that acts upon global version
  * X, but it knows that client A is currently on at least version X + 3, it can transform the edit to act upon
  * version X + 3 and send that transformed edit to client A (which will also apply any necessary transformations locally).
  *
  * Thus, the SharedNotebook must track its global version, the current notebook, and low watermark of the highest global version
  * that all clients have acknowledged. It must also keep a history buffer of all edits between the low watermark and
  * the current global version. Clients can periodically send a status message to the server indicating the current-known
  * global version and the current local version, in order to allow the server to discard edit history.
  *
  * Clients must track the currently known global version, which updates whenever the client receives a foreign edit,
  * and the highest local version which has been acknowledged by the server (either by sending an edit based on it,
  * or through an acknowledgement message). It has to keep its local edit history between that known-acknowledged
  * version and its current version, to allow rebasing new foreign edits.
  *
  * It sounds a lot more complicated than it really is.
  */
trait SharedNotebook[F[_]] {

  def path: String

  /**
    * Open a reference to this shared notebook.
    *
    * @param name A string identifying who is opening the notebook (i.e. their username or email)
    * @param oq   A queue to which messages to the subscriber (such as notifications of foreign updates) will be submitted
    * @return A [[NotebookRef]] which the caller can use to send updates to the shared notebook
    */
  def open(name: String, oq: Enqueue[F, Message]): F[NotebookRef[F]]

  def versions: Stream[F, (Int, Notebook)]
}


class IOSharedNotebook(
  val path: String,
  ref: SignallingRef[IO, (GlobalVersion, Notebook)],            // the Int is the global version, which can wrap around back to zero if necessary
  kernelRef: Ref[IO, Option[Kernel[IO]]],
  updates: Queue[IO, Option[(SubscriberId, NotebookUpdate, Deferred[IO, GlobalVersion])]],   // the canonical series of edits
  updatesTopic: Topic[IO, Option[(GlobalVersion, SubscriberId, NotebookUpdate)]],  // a subscribe-able channel for watching updates,
  kernelFactory: KernelFactory[IO],
  outputMessages: Topic[IO, Message],
  kernelLock: Semaphore[IO]
)(implicit contextShift: ContextShift[IO]) extends SharedNotebook[IO] {

  private val updateBuffer = new VersionBuffer[NotebookUpdate]

  private val statusUpdates = Publish.PublishTopic(outputMessages).contramap[KernelStatusUpdate](KernelStatus(ShortString(path), _))

  // listen to the stream of updates and apply them in order, each one incrementing the global version
  updates.dequeue.unNoneTerminate.zipWithIndex.evalMap {
    case ((subscriberId, update, versionPromise), version) =>
      val newGlobalVersion = (version % Int.MaxValue).toInt
      updateBuffer.add(newGlobalVersion, update)
      applyUpdate(newGlobalVersion, subscriberId, update, versionPromise)
  }.compile.drain.unsafeRunAsyncAndForget()

  private def ensureKernel(): IO[Kernel[IO]] = kernelLock.acquire.bracket { _ =>
    kernelRef.get.flatMap {
      case None => kernelFactory.launchKernel(() => ref.get.map(_._2), statusUpdates).flatMap {
        kernel => kernelRef.set(Some(kernel)).as(kernel)
      }
      case Some(kernel) => IO.pure(kernel)
    }
  }(_ => kernelLock.release)

  // apply a versioned update from the queue, completing its version promise and updating the info about which
  // versions the originating subscriber knows about
  private def applyUpdate(newGlobalVersion: Int, subscriberId: Int, update: NotebookUpdate, versionPromise: Deferred[IO, GlobalVersion]) = ref.get.flatMap {
    case (currentVer, notebook) =>

      // TODO: remove this, just checking for now
      assert(newGlobalVersion - 1 == currentVer, "Version is wrong!")

      val doUpdate = update match {
        case InsertCell(_, _, _, cell, after) => ref.set(newGlobalVersion -> notebook.insertCell(cell, after))
        case DeleteCell(_, _, _, id)          => ref.set(newGlobalVersion -> notebook.deleteCell(id))
        case UpdateCell(_, _, _, id, edits)   => ref.set(newGlobalVersion -> notebook.editCell(id, edits))
        case UpdateConfig(_, _, _, config)    => ref.set(newGlobalVersion -> notebook.copy(config = Some(config)))
        case SetCellLanguage(_, _, _, id, lang) => ref.set(newGlobalVersion -> notebook.updateCell(id)(_.copy(language = lang)))
      }

      for {
        _  <- doUpdate
        sub = subscribers.get(subscriberId)
        _  <- if (sub != null) sub.setKnownVersions(update.globalVersion, update.localVersion) else IO.unit
        _  <- updatesTopic.publish1(Some((newGlobalVersion, subscriberId, update)))
        _  <- versionPromise.complete(newGlobalVersion).attempt
      } yield ()
  }

  // enqueue an update and return a promise for the global version that will eventually represent that update
  private def submitUpdate(subscriberId: SubscriberId, update: NotebookUpdate): IO[Deferred[IO, GlobalVersion]] = for {
    versionPromise <- Deferred[IO, GlobalVersion]
    _              <- updates.enqueue1(Some((subscriberId, update, versionPromise)))
  } yield versionPromise

  private def transformUpdate(update: NotebookUpdate, toVersion: GlobalVersion): NotebookUpdate = {
    updateBuffer.getRange(update.globalVersion, toVersion).foldLeft(update) {
      case (accum, (ver, next)) => accum.rebase(next)
    }
  }

  private val subscribers = new ConcurrentHashMap[Int, Subscriber]()
  private val nextSubscriberId = new AtomicInteger(0)

  def open(name: String, oq: Enqueue[IO, Message]): IO[NotebookRef[IO]] = for {
    subscriberId <- IO(nextSubscriberId.getAndIncrement())
    subscriber    = new Subscriber(subscriberId, name, oq)
    _            <- IO { subscribers.put(subscriberId, subscriber); () }
  } yield subscriber

  def versions: Stream[IO, (GlobalVersion, Notebook)] = ref.discrete

  class Subscriber(subscriberId: Int, name: String, oq: Enqueue[IO, Message]) extends NotebookRef[IO] {
    private val lastLocalVersion = new AtomicInteger(0)
    private val lastGlobalVersion = new AtomicInteger(0)

    private val closeSignal = ReadySignal()

    def setKnownVersions(global: Int, local: Int): IO[Unit] = IO {
      lastGlobalVersion.set(global)
      lastLocalVersion.set(local)
    }

    def lastKnownGlobalVersion: Int = lastGlobalVersion.get()

    // listen for output messages / status updates, and publish them to my client
    outputMessages.subscribe(1024).interruptWhen(closeSignal())
      .to(oq.enqueue).compile.drain.unsafeRunAsyncAndForget()


    // listen for foreign updates, transform them appropriately, and sink to foreignEdits queue
    private val updateFiber = updatesTopic.subscribe(1024).interruptWhen(closeSignal()).unNone.filter(_._2 != subscriberId).evalMap {
      case (globalVersion, _, update) =>
        val knownGlobalVersion = lastGlobalVersion.get()

        if (globalVersion < knownGlobalVersion) {
          // this edit should come before other edits I've already seen - transform it up to knownGlobalVersion
          IO.pure(transformUpdate(update, knownGlobalVersion).withVersions(knownGlobalVersion, lastLocalVersion.get()))
        } else if (globalVersion > knownGlobalVersion) {
          // this edit should come after the last global version I've seen - client will transform locally if necessary
          IO.pure(update.withVersions(globalVersion, lastLocalVersion.get()))
        } else {
          // something has gone terribly wrong.
          IO.raiseError(new IllegalStateException(s"Duplicate global version $globalVersion"))
        }
    }.covaryOutput[Message].to(oq.enqueue).compile.drain.start.unsafeRunSync()

    val path: String = IOSharedNotebook.this.path

    override def get: IO[Notebook] = ref.get.map(_._2)

    override def update(update: NotebookUpdate): IO[Int] = {
      for {
        versionPromise <- submitUpdate(subscriberId, update)
        version        <- versionPromise.get
      } yield version
    }

    override def close(): IO[Unit] = closeSignal.complete.flatMap(_ => updateFiber.join)

    override def isKernelStarted: IO[Boolean] = kernelRef.get.map(_.nonEmpty)

    override def getKernel: IO[Kernel[IO]] = ensureKernel()

    override def shutdownKernel(): IO[Unit] = kernelLock.acquire.bracket { _ =>
      kernelRef.get.flatMap {
        case None => IO.unit
        case Some(kernel) => kernel.shutdown().flatMap {
          _ => kernelRef.set(None)
        }
      }
    }(_ => kernelLock.release)

    override def runCells(ids: List[String]): IO[Stream[IO, Message]] = {

      def runOne(kernel: Kernel[IO], id: String) = {
        val buf = new WindowBuffer[Result](1000)
        Stream.eval(IO.delay(kernel.runCell(id)).flatten).flatten.evalMap {
          result => IO(buf.add(result)) >> IO.pure(CellResult(ShortString(path), TinyString(id), result))
        }.evalTap(outputMessages.publish1).onFinalize {
          ref.update {
            case (ver, nb) => ver -> nb.setResults(id, buf.toList)
          }
        }.drain // the outputs already get published, so we can't really return them in a stream as well...
      }

      for {
        kernel <- getKernel
        runs    = Stream.emits(ids).flatMap(runOne(kernel, _))
      } yield runs
    }

  }

}

object IOSharedNotebook {

  // aliases for disambiguating tuple members
  type SubscriberId = Int
  type GlobalVersion = Int

  def apply(path: String, initial: Notebook, kernelFactory: KernelFactory[IO])(implicit contextShift: ContextShift[IO]): IO[IOSharedNotebook] = for {
    ref          <- SignallingRef[IO, (GlobalVersion, Notebook)](-1 -> initial)
    kernel       <- Ref[IO].of[Option[Kernel[IO]]](None)
    updates      <- Queue.unbounded[IO, Option[(SubscriberId, NotebookUpdate, Deferred[IO, GlobalVersion])]]
    updatesTopic <- Topic[IO, Option[(GlobalVersion, SubscriberId, NotebookUpdate)]](None)
    outputMessages <- Topic[IO, Message](KernelStatus(ShortString(path), KernelBusyState(busy = false, alive = false)))
    kernelLock   <- Semaphore[IO](1)
  } yield new IOSharedNotebook(path, ref, kernel, updates, updatesTopic, kernelFactory, outputMessages, kernelLock)
}

abstract class NotebookRef[F[_] : Monad] {

  def path: String

  def get: F[Notebook]

  /**
    * Apply an update to the notebook
    * @return The global version after the update was applied
    */
  def update(update: NotebookUpdate): F[Int]

  /**
    * Close this reference to the shared notebook
    */
  def close(): F[Unit]

  def isKernelStarted: F[Boolean]

  def getKernel: F[Kernel[F]]

  def currentStatus: F[KernelBusyState] = isKernelStarted.flatMap {
    case true => for {
      kernel <- getKernel
      idle   <- kernel.idle()
    } yield KernelBusyState(!idle, alive = true)

    case false => Monad[F].pure(KernelBusyState(busy = false, alive = false))
  }

  def shutdownKernel(): F[Unit]

  def restartKernel(): F[Kernel[F]] = isKernelStarted.flatMap {
    case true => shutdownKernel().flatMap(_ => getKernel)
    case false => getKernel
  }

  def runCells(ids: List[String]): F[Stream[F, Message]]

}