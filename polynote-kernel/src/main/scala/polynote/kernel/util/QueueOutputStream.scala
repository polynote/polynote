package polynote.kernel.util

import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

import cats.effect.IO
import fs2.Chunk
import fs2.concurrent.Queue

class QueueOutputStream(
  val queue: Queue[IO, Option[Chunk[Byte]]],
  bufSize: Int = 256
) extends OutputStream {

  private val buf: ByteBuffer = ByteBuffer.allocate(bufSize)
  private val closed = new AtomicBoolean(false)

  def write(b: Int): Unit = buf.synchronized {
    if (closed.get()) {
      throw new IllegalStateException("Writing to closed QueueOutputStream")
    }
    if (!buf.hasRemaining) {
      flush()
    }

    buf.put(b.toByte)

    if (b == '\n') {
      flush()
    }
  }

  override def flush(): Unit = {
    super.flush()
    buf.synchronized {
      val len = buf.position()
      if (len > 0) {
        val b = ByteBuffer.allocate(buf.position())
        val view = buf.rewind().limit(len).asInstanceOf[ByteBuffer].slice()
        b.put(view)
        b.rewind()
        buf.rewind()
        buf.limit(bufSize)
        queue.enqueue1(Some(Chunk.byteBuffer(b))).unsafeRunSync()
      }
    }
  }

  override def close(): Unit = buf.synchronized {
    if (!closed.getAndSet(true)) {
      flush()
      super.close()
      queue.enqueue1(None).unsafeRunSync()
    }
  }
}
