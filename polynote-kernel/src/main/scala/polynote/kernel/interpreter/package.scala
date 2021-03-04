package polynote.kernel

import polynote.messages.{DeleteCell, MoveCell, NotebookUpdate}
import zio.{Has, Ref, UIO, ULayer, URIO, ZIO, ZLayer}

package object interpreter {

  type InterpreterState = Has[InterpreterState.Service]
  object InterpreterState {
    trait Service {
      /**
        * @return The current interpreter state
        */
      def getState: UIO[State]

      /**
        * Update the interpreter state, and return the updated state
        */
      def updateState(fn: State => State): UIO[State]

      /**
        * Apply any necessary updates to the interpreter state from the given update
        */
      def updateStateWith(update: NotebookUpdate): UIO[Unit] = update match {
        case DeleteCell(_, _, id)      => updateState(_.remove(id)).unit
        case MoveCell(_, _, id, after) => updateState(_.moveAfter(id, after)).unit
        case _ => ZIO.unit
      }
    }

    private class Impl(ref: Ref[State]) extends Service {
      override def getState: UIO[State] = ref.get
      override def updateState(fn: State => State): UIO[State] = ref.updateAndGet(fn)
    }

    def empty: UIO[Service] = Ref.make(State.empty).map(new Impl(_))
    def emptyLayer: ULayer[InterpreterState] = empty.toLayer
    def access: URIO[InterpreterState, Service] = ZIO.service[Service]
  }

}
