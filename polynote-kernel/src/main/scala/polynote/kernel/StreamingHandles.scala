package polynote.kernel

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

import polynote.kernel.logging.Logging
import polynote.messages.ByteVector32
import polynote.runtime.{StreamingDataRepr, TableOp}
import scodec.bits.ByteVector
import zio.{Semaphore, Task, RIO, ZIO}
import zio.blocking.effectBlocking

trait StreamingHandles {
  val streamingHandles: StreamingHandles.Service
  val sessionID: Int
}

object StreamingHandles {
  trait Service {
    def getStreamData(handleId: Int, count: Int): TaskB[Array[ByteVector32]]
    def modifyStream(handleId: Int, ops: List[TableOp]): TaskB[Option[StreamingDataRepr]]
    def releaseStreamHandle(handleId: Int): TaskB[Unit]
  }

  object Service {
    def make(): Task[Service] = Semaphore.make(1).map {
      semaphore => new Impl(semaphore)
    }

    class Impl(semaphore: Semaphore) extends Service  {
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
                handleIter  = new HandleIterator(handleId, handle.iterator.map(buf => ByteVector32(ByteVector(buf.rewind().asInstanceOf[ByteBuffer]))))
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

  def of(service: Service, sid: Int): StreamingHandles = new StreamingHandles {
    val streamingHandles: Service = service
    override val sessionID: Int = sid
  }

  def make(sessionID: Int): TaskB[StreamingHandles] = Service.make().map(of(_, sessionID))

  def access: RIO[StreamingHandles, StreamingHandles.Service] = ZIO.access[StreamingHandles](_.streamingHandles)

  def getStreamData(handleId: Int, count: Int): RIO[BaseEnv with StreamingHandles, Array[ByteVector32]] =
    access.flatMap(_.getStreamData(handleId, count))

  def modifyStream(handleId: Int, ops: List[TableOp]): RIO[BaseEnv with StreamingHandles, Option[StreamingDataRepr]] =
    access.flatMap(_.modifyStream(handleId, ops))

  def releaseStreamHandle(handleId: Int): RIO[BaseEnv with StreamingHandles, Unit] =
    access.flatMap(_.releaseStreamHandle(handleId))

}
