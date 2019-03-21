package polynote.server

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

import cats.effect.IO
import polynote.messages.ByteVector32
import polynote.runtime.StreamingDataRepr
import scodec.bits.ByteVector

import scala.collection.mutable

/**
  * Manages multiplexed state of streaming data - each instance of StreamingHandleManager has its own iterator per handle
  * at any given time.
  */
class StreamingHandleManager {
  private val streams = new ConcurrentHashMap[Int, HandleIterator]

  private class HandleIterator(handleId: Int, iter: Iterator[ByteVector32]) extends Iterator[ByteVector32] {
    override def hasNext: Boolean = {
      val hasNext = iter.hasNext
      if (!hasNext) {
        streams.remove(handleId)  // release this iterator for GC to make underlying collection available for GC
      }
      hasNext
    }

    override def next(): ByteVector32 =
      iter.next()
  }

  def getStreamData(handleId: Int, count: Int): IO[Array[ByteVector32]] = {
    Option(streams.get(handleId)).map(IO.pure).getOrElse {
      for {
        handleOpt <- IO(StreamingDataRepr.getHandle(handleId))
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
  }

  def releaseStreamHandle(handleId: Int): IO[Unit] = IO(streams.remove(handleId))

}
