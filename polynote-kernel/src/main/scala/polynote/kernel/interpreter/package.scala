package polynote.kernel

import polynote.messages.{DeleteCell, MoveCell, NotebookUpdate}
import zio.blocking.{Blocking, effectBlocking}
import zio.{Has, RIO, Ref, UIO, ULayer, URIO, ZIO, ZLayer}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

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

  def posToLineAndColumn(code: String, pos: Int): (Int, Int) = {
    var line = 1
    var currentPos = 0
    while (currentPos < pos) {
      val nextCR = code.indexOf('\n', currentPos)
      if (nextCR >= pos || nextCR == -1) {
        return (line, pos - currentPos)
      }
      line += 1
      currentPos = nextCR + 1
    }
    (line, 1)
  }

  def readFile(path: Path): RIO[Blocking, String] = effectBlocking {
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
  }

}
