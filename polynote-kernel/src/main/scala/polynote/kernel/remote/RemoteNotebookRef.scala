package polynote.kernel.remote

import polynote.kernel.{BaseEnv, GlobalEnv, NotebookRef, Result}
import polynote.kernel.logging.Logging
import polynote.messages.{CellID, Notebook, NotebookUpdate}
import zio.{IO, RIO, Ref, Task, UIO, URIO, ZIO}
import zio.interop.catz._

/**
  * A NotebookRef implementation used on the remote client. Because its updates come only from the server, it doesn't
  * do any versioning logic, and it ignores results and other operations. It just updates the notebook with the
  * transport's update stream.
  *
  * TODO: should separate into two interfaces, to ensure Kernel isn't using the other methods?
  */
class RemoteNotebookRef private (
  current: Ref[(Int, Notebook)],
  transportClient: TransportClient,
  log: Logging.Service
) extends NotebookRef {

  override def getVersioned: UIO[(Int, Notebook)] = current.get

  override def update(update: NotebookUpdate): IO[NotebookRef.AlreadyClosed, Unit] = updateAndGet(update).unit

  override def updateAndGet(update: NotebookUpdate): IO[NotebookRef.AlreadyClosed, (Int, Notebook)] = current.updateAndGet {
    case (ver, notebook) =>
      try {
        update.globalVersion -> update.applyTo(notebook)
      } catch {
        case err: Throwable =>
          log.errorSync(Some("Notebook update was dropped due to error while applying it"), err)
          (ver, notebook)
      }
  }

  // this is visible for testing (it can't be seen by the environment, which has NotebookRef type)
  def set(notebook: Notebook): UIO[Unit] = current.update(_.copy(_2 = notebook))
  def set(versioned: (Int, Notebook)): UIO[Unit] = current.set(versioned)

  // These other things don't matter on the remote kernel.
  override def addResult(cellID: CellID, result: Result): UIO[Unit] = ZIO.unit
  override def clearResults(cellID: CellID): UIO[Unit] = ZIO.unit
  override def clearAllResults(): IO[NotebookRef.AlreadyClosed, List[CellID]] = ZIO.succeed(Nil)
  override def rename(newPath: String): RIO[BaseEnv with GlobalEnv, String] = get.map(_.path)
  override def close(): Task[Unit] = ZIO.die(new IllegalStateException("Attempt to close RemoteNotebookRef"))
  override val isOpen: UIO[Boolean] = ZIO.succeed(true)
  override def awaitClosed: Task[Unit] = ZIO.die(new IllegalStateException("Attempt to awaitClosed on RemoteNotebookRef"))

  private def init(): URIO[BaseEnv, Unit] = current.get.flatMap {
    case (initialVersion, _) =>
      transportClient.updates
        .dropWhile(_.globalVersion <= initialVersion)
        .evalMap(update)
        .compile.drain.forkDaemon.unit
  }

}

object RemoteNotebookRef {

  def apply(initial: (Int, Notebook), transportClient: TransportClient): URIO[BaseEnv, RemoteNotebookRef] = for {
    current <- Ref.make[(Int, Notebook)](initial)
    log     <- Logging.access
    result   = new RemoteNotebookRef(current, transportClient, log)
    _       <- result.init()
  } yield result

}
