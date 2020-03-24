package polynote.util

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

import zio.RIO
import zio.blocking.{Blocking, effectBlocking}
import zio.ZIO.effectTotal

object GZip {
  def apply(bytes: => Array[Byte]): RIO[Blocking, Array[Byte]] = effectTotal(new ByteArrayOutputStream()).bracket(os => effectTotal(os.close())) {
    bos => effectBlocking {
      val os = new GZIPOutputStream(bos, true)
      os.write(bytes)
      os.flush()
      os.close()
      bos.toByteArray
    }
  }
}
