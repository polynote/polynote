package polynote.runtime

import java.io.DataOutput
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import shapeless.{::, Generic, HList, HNil, Lazy}

import scala.collection.GenTraversable
import scala.language.experimental.macros
import scala.reflect.macros.whitebox

trait DataEncoder[@specialized T] {
  def encode(dataOutput: DataOutput, value: T): Unit
  def size: Int
}

object DataEncoder extends DataEncoderDerivations {

  def instance[@specialized T](fn: (DataOutput, T) => Unit, byteSize: Int = -1): DataEncoder[T] = new DataEncoder[T] {
    def encode(dataOutput: DataOutput, value: T): Unit = fn(dataOutput, value)
    val size: Int = byteSize
  }

  implicit val byte: DataEncoder[Byte] = instance(_ writeByte _, 1)
  implicit val boolean: DataEncoder[Boolean] = instance(_ writeBoolean _, 1)
  implicit val short: DataEncoder[Short] = instance(_ writeShort _, 2)
  implicit val int: DataEncoder[Int] = instance(_ writeInt _, 4)
  implicit val long: DataEncoder[Long] = instance(_ writeLong _, 8)
  implicit val float: DataEncoder[Float] = instance(_ writeFloat _, 4)
  implicit val double: DataEncoder[Double] = instance(_ writeDouble _, 8)
  implicit val string: DataEncoder[String] = instance(_ writeUTF _)
  implicit val byteArray: DataEncoder[Array[Byte]] = instance {
    (out, bytes) =>
      out.writeInt(bytes.length)
      out.write(bytes)
  }

  implicit val byteBuffer: DataEncoder[ByteBuffer] = instance {
    (out, bytes) =>
      val b = bytes.duplicate()
      b.rewind()
      out.writeInt(b.limit())
      val buf = new Array[Byte](1024)
      while (b.hasRemaining) {
        val len = math.min(1024, b.remaining())
        b.get(buf, 0, len)
        out.write(buf, 0, len)
      }
  }

  implicit def array[A](implicit encodeA: DataEncoder[A]): DataEncoder[Array[A]] = instance {
    (output, arr) =>
      output.writeInt(arr.length)
      var i = 0
      while (i < arr.length) {
        encodeA.encode(output, arr(i))
        i += 1
      }
  }

  implicit def traversable[F[X] <: GenTraversable[X], A](implicit encodeA: DataEncoder[A]): DataEncoder[F[A]] = instance {
    (output, seq) =>
      output.writeInt(seq.size)
      seq.foreach(encodeA.encode(output, _))
  }

  private[polynote] class BufferOutput(buf: ByteBuffer) extends DataOutput {
    def write(b: Int): Unit = buf.put(b.toByte)
    def write(b: Array[Byte]): Unit = buf.put(b)
    def write(b: Array[Byte], off: Int, len: Int): Unit = buf.put(b, off, len)
    def writeBoolean(v: Boolean): Unit = buf.put(if (v) 1.toByte else 0.toByte)
    def writeByte(v: Int): Unit = buf.put(v.toByte)
    def writeShort(v: Int): Unit = buf.putShort(v.toShort)
    def writeChar(v: Int): Unit = buf.putChar(v.toChar)
    def writeInt(v: Int): Unit = buf.putInt(v)
    def writeLong(v: Long): Unit = buf.putLong(v)
    def writeFloat(v: Float): Unit = buf.putFloat(v)
    def writeDouble(v: Double): Unit = buf.putDouble(v)
    def writeBytes(s: String): Unit = buf.put(s.toCharArray.map(_.toByte))
    def writeChars(s: String): Unit = s.toCharArray.foreach(c => writeChar(c))
    def writeUTF(s: String): Unit = buf.put(s.getBytes(StandardCharsets.UTF_8))
  }

}

private[runtime] sealed trait DataEncoderDerivations { self: DataEncoder.type =>

  implicit val hnil: DataEncoder[HNil] = instance((_, _) => (), 0)
  implicit def hcons[H, T <: HList](implicit encoderH: DataEncoder[H], encoderT: DataEncoder[T]): DataEncoder[H :: T] =
    instance(
      (output, ht) => {
        encoderH.encode(output, ht.head)
        encoderT.encode(output, ht.tail)
      },
      if (encoderH.size > 0 && encoderT.size > 0) encoderH.size + encoderT.size else -1
    )

  implicit def caseClass[A <: Product, L <: HList](implicit gen: Generic.Aux[A, L], encoderL: Lazy[DataEncoder[L]]): DataEncoder[A] =
    instance((output, a) => encoderL.value.encode(output, gen.to(a)))

}
