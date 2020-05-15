package polynote.testing.kernel

import polynote.kernel.{BaseEnv, GlobalEnv, NotebookRef, Result}
import polynote.messages.{CellID, Notebook, NotebookCell, NotebookUpdate, ShortList}
import zio.{IO, Promise, RIO, Ref, Task, UIO, ZIO}

// a notebook ref that's only in-memory
class MockNotebookRef private(
  val current: Ref[(Int, Notebook)],
  closed: Promise[Throwable, Unit],
  saveTo: ((Int, Notebook)) => UIO[Unit]
) extends NotebookRef {
  def set(versioned: (Int, Notebook)): UIO[Unit] = current.set(versioned)
  override def getVersioned: UIO[(Int, Notebook)] = current.get
  override def update(update: NotebookUpdate): IO[NotebookRef.AlreadyClosed, Unit] = updateAndGet(update).unit

  private def updateAndGetCurrent(update: ((Int, Notebook)) => (Int, Notebook)) =
    current.updateAndGet(update).tap(saveTo)

  private def updateCurrent(update: ((Int, Notebook)) => (Int, Notebook)) =
    updateAndGetCurrent(update).unit

  override def updateAndGet(update: NotebookUpdate): IO[NotebookRef.AlreadyClosed, (Int, Notebook)] = updateAndGetCurrent {
    case (ver, nb) => (ver + 1) -> update.applyTo(nb)
  }

  override def addResult(cellID: CellID, result: Result): IO[NotebookRef.AlreadyClosed, Unit] = updateCurrent {
    case (ver, nb) => ver -> nb.updateCell(cellID)(result.toCellUpdate)
  }

  override def clearResults(cellID: CellID): IO[NotebookRef.AlreadyClosed, Unit] = updateCurrent {
    case (ver, nb) => ver -> nb.updateCell(cellID)(_.copy(results = ShortList.Nil))
  }

  override def clearAllResults(): IO[NotebookRef.AlreadyClosed, List[CellID]] = current.modify {
    case (ver, notebook) =>
      val (clearedIds, updatedCells) = notebook.cells.foldRight((List.empty[CellID], List.empty[NotebookCell])) {
        case (cell, (clearedIds, updatedCells)) =>
          if (cell.results.nonEmpty)
            (cell.id :: clearedIds) -> (cell.copy(results = ShortList(Nil)) :: updatedCells)
          else
            clearedIds -> (cell :: updatedCells)
      }
      clearedIds -> (ver -> notebook.copy(cells = ShortList(updatedCells)))
  } <* (current.get >>= saveTo)

  override def rename(newPath: String): RIO[BaseEnv with GlobalEnv, String] = updateCurrent {
    case (ver, nb) => ver -> nb.copy(path = newPath)
  }.as(newPath)

  override def close(): Task[Unit] = closed.succeed(()).unit

  override def isOpen: UIO[Boolean] = closed.isDone.map(!_)

  override def awaitClosed: Task[Unit] = closed.await
}

object MockNotebookRef {
  def apply(notebook: Notebook, saveTo: ((Int, Notebook)) => UIO[Unit] = _ => ZIO.unit, version: Int = 0): UIO[MockNotebookRef] = for {
    current <- Ref.make(version -> notebook)
    closed  <- Promise.make[Throwable, Unit]
  } yield new MockNotebookRef(current, closed, saveTo)
}