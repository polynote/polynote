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

  /**
    * @param code A code string
    * @param pos  An offset into the code string
    * @return A tuple containing the (1-based) line number and (0-based) column number that corresponds to the given offset.
    */
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
    (line, 0)
  }

  def readFile(path: Path): RIO[Blocking, String] = effectBlocking {
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
  }

  /**
    * FileZipArchive#allDirs was changed to a java.util.Map in Scala 2.12. So these two extension methods are to give a
    * consistent syntax between them
    */
  implicit class ScalaMapCompat[K, V](private val self: scala.collection.mutable.HashMap[K, V]) extends AnyVal {
    def getOpt(key: K): Option[V] = self.get(key)
    def valuesCompat: Seq[V] = self.values.toSeq
  }

  implicit class JavaMapCompat[K, V](private val self: java.util.Map[K, V]) extends AnyVal {
    import scala.collection.JavaConverters._
    def getOpt(key: K): Option[V] = Option(self.get(key))
    def valuesCompat: Seq[V] = self.values().asScala.toSeq
  }

}
