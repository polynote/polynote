package polynote.kernel.util

import java.io.{OutputStream, PrintStream}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import polynote.kernel.{Output, Result}

class ResultOutputStream(publishSync: Result => Unit, bufSize: Int = 65536) extends OutputStream {
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
    if (buf.hasRemaining) {
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
  }

  override def close(): Unit = buf.synchronized {
    if (!closed.getAndSet(true)) {
      flush()
      super.close()
    }
  }

}

class ResultPrintStream(publishSync: Result => Unit, bufSize: Int = 65536)(private val outputStream: OutputStream = new ResultOutputStream(publishSync, bufSize)) extends PrintStream(outputStream, true, "UTF-8") {
  override def println(value: String): Unit = {
    outputStream.flush()
    publishSync(Output("text/plain; rel=stdout", value + "\n"))
  }

  override def print(s: String): Unit = {
    outputStream.flush()
    publishSync(Output("text/plain; rel=stdout", s))
  }

  override def println(x: AnyRef): Unit = println(String.valueOf(x))
}
