package polynote.kernel

import polynote.messages.{Notebook, NotebookUpdate}
import zio.stream.{Take, ZStream, Stream}
import zio.{Promise, Queue, Ref, UIO}

class NotebookRef private (
  current: Ref[(Int, Notebook)],
  pending: Queue[Take[Nothing, NotebookUpdate]],

  closed: Promise[Nothing, Unit]
) {

  def update(update: NotebookUpdate): UIO[Unit] = pending.offer(Take.Value(update)).unit

  def versioned: Stream[Nothing, (Int, Notebook)]


  def close(): UIO[Unit] = pending.offer(Take.End) *> closed.succeed(()).unit

  private def init(): UIO[Unit] = ZStream.fromQueue(pending).unTake.foreach {
    update => current.update {
      case (ver, notebook) => (update.globalVersion, update.applyTo(notebook))
    }
  }
}

object NotebookRef {
  def apply(version: Int, notebook: Notebook): UIO[NotebookRef] = for {
    current <- Ref.make[(Int, Notebook)]((version, notebook))
    pending <- Queue.unbounded[Take[Nothing, NotebookUpdate]]  // Note: NotebookRef#update assumes pending is unbounded
    closed  <- Promise.make[Nothing, Unit]
    ref      = new NotebookRef(current, pending, closed)
    _       <- ref.init()
  } yield ref

  def apply(notebook: Notebook): UIO[NotebookRef] = apply(0, notebook)
}
