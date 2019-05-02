package polynote.kernel.util

import java.io.{OutputStream, PrintStream}
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
      // TODO: Trouble is, we close the output right after running all the code, but what if there are background threads printing to stuff?
      //       I guess we might want to keep the channel open and keep streaming output? But how to know when it's done?
      //throw new IllegalStateException("Writing to closed QueueOutputStream")
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

class QueuePrintStream(val queue: Queue[IO, Option[Chunk[Byte]]], bufSize: Int = 256) extends PrintStream(new QueueOutputStream(queue, bufSize)) {

  override def print(s: String): Unit = {
    super.print(s)
    if (s startsWith "\r")
      flush()
  }



}
