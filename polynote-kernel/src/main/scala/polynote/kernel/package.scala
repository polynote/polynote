package polynote

import java.nio.{Buffer, ByteBuffer}
import java.util.concurrent.ConcurrentHashMap
import polynote.kernel.environment.{Config, CurrentNotebook, CurrentRuntime, CurrentTask, PublishResult, PublishStatus}
import polynote.kernel.interpreter.{Interpreter, InterpreterState}
import polynote.kernel.logging.Logging
import polynote.kernel.task.TaskManager
import polynote.messages.{ByteVector32, Notebook}
import polynote.runtime.{StreamingDataRepr, TableOp}
import scodec.{Attempt, Codec, DecodeResult, Err}
import scodec.bits.{BitVector, ByteVector}
import zio.{Chunk, Has, Promise, RIO, Semaphore, Task, UIO, ZIO}
import zio.blocking.{Blocking, effectBlocking}
import zio.clock.Clock
import zio.random.Random
import zio.stream.{Take, ZStream}
import zio.system.System

package object kernel {

  type BaseEnv = Blocking with Clock with System with Logging with Random

  // some type aliases jut to avoid env clutter
  type TaskB[+A] = RIO[BaseEnv, A]
  type TaskC[+A] = RIO[BaseEnv with GlobalEnv with CellEnv, A]
  type TaskG[+A] = RIO[BaseEnv with GlobalEnv, A]

  type GlobalEnv = Config with Interpreter.Factories with Kernel.Factory

  // CellEnv is provided to the kernel by its host when a cell is being run
  type CellEnv = CurrentNotebook with InterpreterState with TaskManager with PublishStatus with PublishResult

  // InterpreterEnv is provided to the interpreter by the Kernel when running cells
  type InterpreterEnv = Blocking with PublishResult with PublishStatus with CurrentTask with CurrentRuntime

  /**
    * Filter syntax for ZIO[R, Unit, A] – basically it's OptionT
    */
  final implicit class ZIOOptionSyntax[R, A](val self: ZIO[R, Unit, A]) extends AnyVal {
    def withFilter(predicate: A => Boolean): ZIO[R, Unit, A] = self.filterOrFail(predicate)(())
  }

  /**
    * Some additional syntax for ZIO
    */
  final implicit class ZIOSyntax[R, E, A](val self: ZIO[R, E, A]) extends AnyVal {
    def widen[A1 >: A]: ZIO[R, E, A1] = self
  }

  final implicit class RIOSyntax[R, A](val self: ZIO[R, Throwable, A]) extends AnyVal {
    def withFilter(predicate: A => Boolean): ZIO[R, Throwable, A] = self.filterOrFail(predicate)(new MatchError("Predicate is not satisfied"))
  }

  def effectMemoize[A](thunk: => A): Task[A] = {
    lazy val a = thunk
    ZIO(a)
  }

  def withContextClassLoaderIO[A](cl: ClassLoader)(thunk: => A): RIO[Blocking, A] =
    zio.blocking.effectBlocking(withContextClassLoader(cl)(thunk))

  def withContextClassLoader[A](cl: ClassLoader)(thunk: => A): A = {
    val thread = Thread.currentThread()
    val prevCL = thread.getContextClassLoader
    thread.setContextClassLoader(cl)
    try thunk finally {
      thread.setContextClassLoader(prevCL)
    }
  }



  def streamCodec[R, E, A](codec: Codec[A]): ZStream[R, Throwable, BitVector] => ZStream[R, Throwable, A] = _.mapAccumM(BitVector.empty) {
    (leftoverBits, newBits) =>
      val allTogetherNow = leftoverBits ++ newBits
      codec.decode(allTogetherNow) match {
        case Attempt.Successful(DecodeResult(value, remainder)) => ZIO.succeed((remainder, Some(value)))
        case Attempt.Failure(_: Err.InsufficientBits)           => ZIO.succeed((allTogetherNow, None))
        case Attempt.Failure(err)                               => ZIO.fail(CodecError(err))
      }
  }.collectSome

  type StreamingHandles = Has[StreamingHandles.Service]


  object StreamingHandles {
    trait Service {
      def getStreamData(handleId: Int, count: Int): TaskB[Array[ByteVector32]]
      def modifyStream(handleId: Int, ops: List[TableOp]): TaskB[Option[StreamingDataRepr]]
      def releaseStreamHandle(handleId: Int): TaskB[Unit]
      def sessionID: Int
    }

    object Service {
      def make(sessionID: Int): Task[Service] = Semaphore.make(1).map {
        semaphore => new Impl(semaphore, sessionID)
      }

      class Impl(semaphore: Semaphore, val sessionID: Int) extends Service  {
        // hold a reference to modified streaming reprs until they're consumed, otherwise they'll get GCed before they can get used
        private val modifiedStreams = new ConcurrentHashMap[Int, StreamingDataRepr]()

        // currently running stream iterators
        private val streams = new ConcurrentHashMap[Int, HandleIterator]

        private def takeElems(iter: HandleIterator, count: Int) =
          effectBlocking(iter.take(count).toArray).onError(_ => ZIO(modifiedStreams.remove(iter.handleId)).ignore)

        override def getStreamData(handleId: Int, count: Int): TaskB[Array[ByteVector32]] =
          Option(streams.get(handleId)) match {
            case Some(iter) => takeElems(iter, count)
            case None => semaphore.withPermit {
              ZIO(Option(streams.get(handleId))).flatMap {
                case Some(iter) => effectBlocking(iter.take(count).toArray)
                case None => for {
                  handle     <- ZIO.effectTotal(StreamingDataRepr.getHandle(handleId)).get.mapError(_ => new NoSuchElementException(s"Invalid streaming handle ID $handleId"))
                    iter       <- effectBlocking(handle.iterator)
                    handleIter  = new HandleIterator(handleId, handle.iterator.map(buf => ByteVector32(ByteVector((buf:Buffer).rewind().asInstanceOf[ByteBuffer]))))
                    _          <- ZIO.effectTotal(streams.put(handleId, handleIter))
                    arr        <- takeElems(handleIter, count)
                } yield arr
              }
            }
          }

        override def releaseStreamHandle(handleId: Int): Task[Unit] = ZIO.effectTotal(streams.remove(handleId)).unit

        override def modifyStream(handleId: Int, ops: List[TableOp]): TaskB[Option[StreamingDataRepr]] = {
          ZIO(StreamingDataRepr.getHandle(handleId)).flatMap {
            case None => ZIO.succeed(None)
            case Some(handle) =>
              ZIO.fromEither(handle.modify(ops))
                .map(StreamingDataRepr.fromHandle).map(Some(_))
                .catchSome {
                  case err: UnsupportedOperationException => ZIO.succeed(None)
                }.tap {
                case Some(handle) => ZIO(modifiedStreams.put(handle.handle, handle))
                case None => ZIO.unit
              }.tapError(Logging.error("Error modifying streaming handle", _))
          }
        }

        private class HandleIterator(val handleId: Int, iter: Iterator[ByteVector32]) extends Iterator[ByteVector32] {
          override def hasNext: Boolean = {
            val hasNext = iter.hasNext
            if (!hasNext) {
              streams.remove(handleId) // release this iterator for GC to make underlying collection available for GC
              modifiedStreams.remove(handleId) // if it was a modified stream, release that reference as well
            }
            hasNext
          }

          override def next(): ByteVector32 = iter.next()
        }

      }
    }

    def make(sessionID: Int): TaskB[Service] = Service.make(sessionID)

    def access: RIO[StreamingHandles, StreamingHandles.Service] = ZIO.access[StreamingHandles](_.get)

    def getStreamData(handleId: Int, count: Int): RIO[BaseEnv with StreamingHandles, Array[ByteVector32]] =
      access.flatMap(_.getStreamData(handleId, count))

    def modifyStream(handleId: Int, ops: List[TableOp]): RIO[BaseEnv with StreamingHandles, Option[StreamingDataRepr]] =
      access.flatMap(_.modifyStream(handleId, ops))

    def releaseStreamHandle(handleId: Int): RIO[BaseEnv with StreamingHandles, Unit] =
      access.flatMap(_.releaseStreamHandle(handleId))

  }

}
