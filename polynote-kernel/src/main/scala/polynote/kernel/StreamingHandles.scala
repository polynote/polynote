package polynote.kernel

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

import polynote.kernel.logging.Logging
import polynote.messages.ByteVector32
import polynote.runtime.{StreamingDataRepr, TableOp}
import scodec.bits.ByteVector
import zio.{Semaphore, Task, TaskR, ZIO}
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
      override def getStreamData(handleId: Int, count: Int): TaskB[Array[ByteVector32]] =
        Option(streams.get(handleId)) match {
          case Some(iter) => effectBlocking(iter.take(count).toArray)
          case None => semaphore.withPermit {
            ZIO(Option(streams.get(handleId))).flatMap {
              case Some(iter) => effectBlocking(iter.take(count).toArray)
              case None => for {
                handle     <- ZIO.effectTotal(StreamingDataRepr.getHandle(handleId)).get.mapError(_ => new NoSuchElementException(s"Invalid streaming handle ID $handleId"))
                iter       <- effectBlocking(handle.iterator)
                handleIter  = new HandleIterator(handleId, handle.iterator.map(buf => ByteVector32(ByteVector(buf.rewind().asInstanceOf[ByteBuffer]))))
                _          <- ZIO.effectTotal(streams.put(handleId, handleIter))
                arr        <- effectBlocking(handleIter.take(count).toArray)
              } yield arr
            }
          }
        }

      override def releaseStreamHandle(handleId: Int): Task[Unit] = ZIO.effectTotal(streams.remove(handleId)).unit

      override def modifyStream(handleId: Int, ops: List[TableOp]): TaskB[Option[StreamingDataRepr]] = {
        ZIO(StreamingDataRepr.getHandle(handleId)).flatMap {
          case None => ZIO.succeed(None)
          case Some(handle) => ZIO.fromEither(handle.modify(ops)).map(StreamingDataRepr.fromHandle).map(Some(_)).catchSome {
            case err: UnsupportedOperationException => ZIO.succeed(None)
          }.tapError(Logging.error("Error modifying streaming handle", _))
        }
      }

      private val streams = new ConcurrentHashMap[Int, HandleIterator]

      private class HandleIterator(handleId: Int, iter: Iterator[ByteVector32]) extends Iterator[ByteVector32] {
        override def hasNext: Boolean = {
          val hasNext = iter.hasNext
          if (!hasNext) {
            streams.remove(handleId) // release this iterator for GC to make underlying collection available for GC
          }
          hasNext
        }

        override def next(): ByteVector32 =
          iter.next()
      }

    }
  }

  def of(service: Service, sid: Int): StreamingHandles = new StreamingHandles {
    val streamingHandles: Service = service
    override val sessionID: Int = sid
  }

  def make(sessionID: Int): TaskB[StreamingHandles] = Service.make().map(of(_, sessionID))

  def access: TaskR[StreamingHandles, StreamingHandles.Service] = ZIO.access[StreamingHandles](_.streamingHandles)

  def getStreamData(handleId: Int, count: Int): TaskR[BaseEnv with StreamingHandles, Array[ByteVector32]] =
    access.flatMap(_.getStreamData(handleId, count))

  def modifyStream(handleId: Int, ops: List[TableOp]): TaskR[BaseEnv with StreamingHandles, Option[StreamingDataRepr]] =
    access.flatMap(_.modifyStream(handleId, ops))

  def releaseStreamHandle(handleId: Int): TaskR[BaseEnv with StreamingHandles, Unit] =
    access.flatMap(_.releaseStreamHandle(handleId))

}
