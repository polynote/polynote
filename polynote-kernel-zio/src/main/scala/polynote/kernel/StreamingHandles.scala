package polynote.kernel

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

import polynote.messages.ByteVector32
import polynote.runtime.{StreamingDataRepr, TableOp}
import scodec.bits.ByteVector
import zio.{Task, TaskR, ZIO}

trait StreamingHandles {
  val streamingHandles: StreamingHandles.Service
}

object StreamingHandles {
  trait Service {
    def getStreamData(handleId: Int, count: Int): Task[Array[ByteVector32]]
    def modifyStream(handleId: Int, ops: List[TableOp]): Task[Option[StreamingDataRepr]]
    def releaseStreamHandle(handleId: Int): Task[Unit]
  }

  object Service {
    def make(): Service = new Service {
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

      override def getStreamData(handleId: Int, count: Int): Task[Array[ByteVector32]] =
        Option(streams.get(handleId)).map(ZIO.succeed).getOrElse {
          for {
            handleOpt <- ZIO(StreamingDataRepr.getHandle(handleId))
          } yield handleOpt match {
            case Some(handle) =>
              val iter = new HandleIterator(handleId, handle.iterator.map(buf => ByteVector32(ByteVector(buf.rewind().asInstanceOf[ByteBuffer]))))
              streams.put(handleId, iter)
              iter

            case None => Iterator.empty
          }
        }.map {
          iter =>
            iter.take(count).toArray
        }

      override def releaseStreamHandle(handleId: Int): Task[Unit] = ZIO(streams.remove(handleId)).unit

      override def modifyStream(handleId: Int, ops: List[TableOp]): Task[Option[StreamingDataRepr]] = {
        ZIO(StreamingDataRepr.getHandle(handleId)).flatMap {
          case None => ZIO.succeed(None)
          case Some(handle) => ZIO.fromEither(handle.modify(ops)).map(StreamingDataRepr.fromHandle).map(Some(_))
        }
      }
    }
  }

  def of(service: Service): StreamingHandles = new StreamingHandles {
    val streamingHandles: Service = service
  }

  def make(): StreamingHandles = of(Service.make())

  def access: TaskR[StreamingHandles, StreamingHandles.Service] = ZIO.access[StreamingHandles](_.streamingHandles)

  def getStreamData(handleId: Int, count: Int): TaskR[StreamingHandles, Array[ByteVector32]] =
    access.flatMap(_.getStreamData(handleId, count))

  def modifyStream(handleId: Int, ops: List[TableOp]): TaskR[StreamingHandles, Option[StreamingDataRepr]] =
    access.flatMap(_.modifyStream(handleId, ops))

  def releaseStreamHandle(handleId: Int): TaskR[StreamingHandles, Unit] =
    access.flatMap(_.releaseStreamHandle(handleId))

}
