package polynote

import cats.effect.concurrent.Ref
import fs2.Stream
import polynote.config.PolynoteConfig
import polynote.kernel.util.{KernelContext, Publish}
import polynote.messages.{CellID, Notebook}
import polynote.runtime.KernelRuntime
import zio.{Task, TaskR, ZIO}
import zio.blocking.Blocking

package object kernel {

  trait Broadcasts {
    val statusUpdates: Publish[Task, KernelStatusUpdate]
  }

  trait CurrentTask {
    val currentTask: Ref[Task, TaskInfo]
  }

  object CurrentTask {
    def apply(taskInfoRef: Ref[Task, TaskInfo]): CurrentTask = new CurrentTask {
      val currentTask: Ref[Task, TaskInfo] = taskInfoRef
    }
  }

  trait CurrentCell {
    val currentCellId: CellID
  }

  trait CurrentRuntime {
    val currentRuntime: KernelRuntime
  }

  object CurrentRuntime {
    object NoRuntime extends KernelRuntime(
      new KernelRuntime.Display {
        def content(contentType: String, content: String): Unit = ()
      },
      (_, _) => (),
      _ => ()
    )

    object NoCurrentRuntime extends CurrentRuntime {
      val currentRuntime: KernelRuntime = NoRuntime
    }
  }

  trait CurrentNotebook {
    val currentNotebook: Ref[Task, Notebook]
  }

  implicit class StreamOps[A](val stream: Stream[Task, A]) {

    /**
      * Convenience method to terminate (rather than interrupt) a stream after a given predicate is met. In contrast to
      * [[Stream.interruptWhen]], this allows the stream to finish processing all elements up to and including the
      * element that satisfied the predicate, whereas interruptWhen ungracefully terminates it at once.
      */
    def terminateAfter(fn: A => Boolean): Stream[Task, A] = stream.flatMap {
      case end if fn(end) => Stream.emits(List(Some(end), None))
      case notEnd         => Stream.emit(Some(notEnd))
    }.unNoneTerminate

  }

  def withContextClassLoaderIO[A](cl: ClassLoader)(thunk: => A): TaskR[Blocking, A] =
    zio.blocking.effectBlocking(withContextClassLoader(cl)(thunk))

  def withContextClassLoader[A](cl: ClassLoader)(thunk: => A): A = {
    val thread = Thread.currentThread()
    val prevCL = thread.getContextClassLoader
    try thunk finally {
      thread.setContextClassLoader(prevCL)
    }
  }


}
