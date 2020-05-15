package polynote.kernel


import polynote.messages.{CellID, Notebook, NotebookUpdate}
import zio.{IO, RIO, Task, UIO, ZIO}

/**
  * A reference to a notebook in memory. If the notebook was loaded from a repository, making changes to the reference
  * will cause the changes to be persisted in an implementation-dependent fashion.
  */
trait NotebookRef {

  /**
    * Return the latest version and notebook
    */
  def getVersioned: UIO[(Int, Notebook)]

  /**
    * Return the latest notebook
    */
  def get: UIO[Notebook] = getVersioned.map(_._2)

  def path: UIO[String] = get.map(_.path)

  /**
    * Apply a notebook update. If this NotebookRef is responsible for versioning, the version is incremented. The
    * update may not be applied immediately; use [[updateAndGet]] to block until the update has been applied.
    */
  def update(update: NotebookUpdate): IO[NotebookRef.AlreadyClosed, Unit]

  /**
    * Apply a notebook update and return the new version and notebook. If this NotebookRef is responsible for versioning,
    * the version is incremented.
    */
  def updateAndGet(update: NotebookUpdate): IO[NotebookRef.AlreadyClosed, (Int, Notebook)]

  /**
    * Append a [[Result]] to the given cell of the notebook
    */
  def addResult(cellID: CellID, result: Result): IO[NotebookRef.AlreadyClosed, Unit]

  /**
    * Clear results from the given cell of the notebook
    */
  def clearResults(cellID: CellID): IO[NotebookRef.AlreadyClosed, Unit]

  /**
    * Clear results from all cells of the notebook, and return a list of CellIDs whose results were modified
    */
  def clearAllResults(): IO[NotebookRef.AlreadyClosed, List[CellID]]

  def rename(newPath: String): RIO[BaseEnv with GlobalEnv, String]

  /**
    * Close the notebook.
    */
  def close(): Task[Unit]

  def isOpen: UIO[Boolean]

  /**
    * Wait for the ref to be closed. Succeeds with Unit if the ref closes normally; fails with error otherwise
    */
  def awaitClosed: Task[Unit]
}



object NotebookRef {

  /**
    * A [[NotebookRef]] which ignores all changes
    */
  final class Const(notebook: Notebook) extends NotebookRef {
    override def getVersioned: UIO[(Int, Notebook)] = ZIO.succeed((0, notebook))
    override def addResult(cellID: CellID, result: Result): UIO[Unit] = ZIO.unit
    override def clearAllResults(): UIO[List[CellID]] = ZIO.succeed(Nil)
    override def clearResults(cellID: CellID): UIO[Unit] = ZIO.unit
    override def update(update: NotebookUpdate): UIO[Unit] = ZIO.unit
    override def updateAndGet(update: NotebookUpdate): UIO[(Int, Notebook)] = getVersioned
    override def close(): UIO[Unit] = ZIO.unit
    override def rename(newPath: String): UIO[String] = ZIO.succeed(notebook.path)
    override val isOpen: UIO[Boolean] = ZIO.succeed(true)
    override def awaitClosed: Task[Unit] = ZIO.unit
  }

  case class AlreadyClosed(path: Option[String]) extends Throwable(s"Notebook ${path.map(_ + " ").getOrElse("")}is already closed")
  val alreadyClosed: IO[AlreadyClosed, Nothing] = ZIO.fail(AlreadyClosed(None))

}
