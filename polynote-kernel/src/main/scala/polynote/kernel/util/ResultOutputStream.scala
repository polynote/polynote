package polynote.kernel.util

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

import polynote.kernel.{Output, Result}

class ResultOutputStream(publishSync: Result => Unit, bufSize: Int = 1024) extends OutputStream {
  private val buf: ByteBuffer = ByteBuffer.allocate(bufSize)
  private val closed = new AtomicBoolean(false)

  def write(b: Int): Unit = buf.synchronized {
    if (!buf.hasRemaining) {
      flush()
    }

    buf.put(b.toByte)
  }

  override def flush(): Unit = {
    super.flush()
    buf.synchronized {
      val len = buf.position()
      if (len > 0) {
        val b = ByteBuffer.allocate(buf.position())
        val arr = new Array[Byte](buf.position())
        buf.rewind()
        buf.get(arr)
        publishSync(Output("text/plain; rel=stdout", new String(arr, StandardCharsets.UTF_8)))
        buf.rewind()
      }
    }
  }

  override def close(): Unit = buf.synchronized {
    if (!closed.getAndSet(true)) {
      flush()
      super.close()
    }
  }

}
