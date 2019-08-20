package polynote.kernel

import cats.effect.concurrent.Ref
import polynote.kernel.util.Publish
import polynote.messages.CellID
import polynote.runtime.KernelRuntime
import zio.{Runtime, Task, TaskR, ZIO}
import zio.blocking.Blocking
import zio.clock.Clock

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.tools.nsc.interactive.Global

package object interpreter {

  trait State {
    import State.Root
    def id: CellID
    def prev: State
    def values: List[ResultValue]

    // TODO: make this protected, public API should prevent you from removing Root from the chain
    def withPrev(prev: State): State

    def collect[A](pf: PartialFunction[State, A]): List[A] = {
      val buf = new ListBuffer[A]
      var state = this
      while (state != Root) {
        if (pf.isDefinedAt(state)) {
          buf += pf(state)
        }
        state = state.prev
      }
      buf.toList
    }

    def scope: List[ResultValue] = {
      val visible = new mutable.ListMap[String, ResultValue]
      var state = this
      while (state != Root) {
        state.values.foreach {
          rv => if (!visible.contains(rv.name)) {
            visible.put(rv.name, rv)
          }
        }
        state = state.prev
      }
      visible.values.toList
    }

    /**
      * Find the furthest state in the chain where the predicate still holds
      */
    def rewindWhile(predicate: State => Boolean): State = {
      var s = this
      while(s != Root && predicate(s)) {
        s = s.prev
      }
      s
    }

    /**
      * Insert the given state after the given state, such that the state with the given ID becomes the given state's
      * predecessor. If no state exists with the given ID, it will be inserted after Root.
      */
    def insert(after: CellID, state: State): State = if (after == id || this == Root) {
      state.withPrev(this)
    } else withPrev(prev.insert(after, state))

    /**
      * Replace the state with the same ID as the given state with the given state
      */
    def replace(state: State): State = if (state.id == id) {
      state.withPrev(prev)
    } else if (this != Root) {
      withPrev(prev.replace(state))
    } else this

    /**
      * Replace the state with the same ID as the given state with the given state. If a state with the given state's
      * predecessor is found before a state with the same ID as the given state, the given state will be inserted
      * between the predecessor and its successor.
      */
    def insertOrReplace(state: State): State = if (state.id == id) {
      state.withPrev(prev)
    } else if (state.prev.id == prev.id) {
      withPrev(state.withPrev(prev))
    } else if (this eq Root) {
      state.withPrev(this)
    } else withPrev(prev.insertOrReplace(state))

    def at(id: CellID): Option[State] = {
      var result: Option[State] = None
      var s = this
      while ((result eq None) && s != Root) {
        if (s.id == id) {
          result = Some(s)
        }
        s = s.prev
      }
      result
    }
  }

  object State {

    case object Root extends State {
      val id: CellID = Short.MinValue
      val prev: State = this
      val values: List[ResultValue] = Nil
      def withPrev(prev: State): Root.type = this
    }

    final case class Id(id: CellID, prev: State, values: List[ResultValue]) extends State {
      def withPrev(prev: State): State = copy(prev = prev)
    }

    def id(id: Int, prev: State = Root, values: List[ResultValue] = Nil): State = Id(id.toShort, prev, values)

  }

  trait PublishResults {
    val publishResult: Result => Task[Unit]
    val unsafePublishResultSync: Task[Result => Unit] = ZIO.runtime.map {
      runtime => result => runtime.unsafeRunSync(publishResult(result)).toEither.fold(throw _, _ => ())
    }
    val unsafePublishResultAsync: Task[Result => Unit] = ZIO.runtime.map {
      runtime => result => runtime.unsafeRunAsync_(publishResult(result))
    }
  }

  // TODO: can we do something a little less boilerplatey for building up and tearing down environments?
  class InterpreterEnv[Outer <: Blocking with Clock with Broadcasts](
    outerEnv: Outer,
    val currentCellId: CellID,
    val publishResult: Result => Task[Unit],
    val currentTask: Ref[Task, TaskInfo],
    runtime: Runtime[Any]
  ) extends Blocking with Clock with PublishResults with Broadcasts with CurrentTask with CurrentRuntime with CurrentCell {
    val blocking: Blocking.Service[Any] = outerEnv.blocking
    val clock: Clock.Service[Any] = outerEnv.clock
    val statusUpdates: Publish[Task, KernelStatusUpdate] = outerEnv.statusUpdates
    val currentRuntime: KernelRuntime = new KernelRuntime(
      new KernelRuntime.Display {
        def content(contentType: String, content: String): Unit = runtime.unsafeRunAsync_(publishResult(Output(contentType, content)))
      },
      (frac, detail) => runtime.unsafeRunAsync_(currentTask.update(_.progress(frac, Option(detail).filter(_.nonEmpty)))),
      optPos => statusUpdates.publish1(ExecutionStatus(currentCellId, optPos.map(boxed => (boxed._1.intValue, boxed._2.intValue()))))
    )
  }

  object InterpreterEnv {
    def withPublish[A](publish: Result => Task[Unit])(
      task: TaskR[Blocking with Clock with PublishResults with Broadcasts with CurrentTask with CurrentRuntime, A]
    ): TaskR[Blocking with CurrentCell with Clock with Broadcasts with CurrentTask, A] = for {
      runtime <- ZIO.runtime
      cell    <- ZIO.access[CurrentCell](_.currentCellId)
      result  <- task.provideSome[Blocking with Clock with Broadcasts with CurrentTask] {
        outerEnv => new InterpreterEnv(outerEnv, cell, publish, outerEnv.currentTask, runtime)
      }
    } yield result
  }

}
