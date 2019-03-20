package polynote.runtime

import java.io.DataOutput
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import shapeless.labelled.FieldType
import shapeless.{::, Generic, HList, HNil, LabelledGeneric, Lazy, Witness}

import scala.collection.GenTraversable
import scala.reflect.macros.whitebox

trait DataEncoder[@specialized T] {
  def encode(dataOutput: DataOutput, value: T): Unit
  def encodeAnd(dataOutput: DataOutput, value: T): DataOutput = {
    encode(dataOutput, value)
    dataOutput
  }
  def dataType: DataType
  def sizeOf(t: T): Int
}

object DataEncoder extends DataEncoderDerivations {

  def sizedInstance[@specialized T](typ: DataType, size: T => Int)(fn: (DataOutput, T) => Unit): DataEncoder[T] = new DataEncoder[T] {
    def encode(dataOutput: DataOutput, value: T): Unit = fn(dataOutput, value)
    val dataType: DataType = typ
    override def sizeOf(t: T): Int = size(t)
  }

  def instance[@specialized T](typ: DataType)(fn: (DataOutput, T) => Unit): DataEncoder[T] = sizedInstance[T](typ, _ => typ.size)(fn)

  implicit val byte: DataEncoder[Byte] = instance(ByteType)(_ writeByte _)
  implicit val boolean: DataEncoder[Boolean] = instance(BoolType)(_ writeBoolean _)
  implicit val short: DataEncoder[Short] = instance(ShortType)(_ writeShort _)
  implicit val int: DataEncoder[Int] = instance(IntType)(_ writeInt _)
  implicit val long: DataEncoder[Long] = instance(LongType)(_ writeLong _)
  implicit val float: DataEncoder[Float] = instance(FloatType)(_ writeFloat _)
  implicit val double: DataEncoder[Double] = instance(DoubleType)(_ writeDouble _)
  implicit val string: DataEncoder[String] = instance(StringType) {
    (out, str) =>
      out.writeInt(str.length)
      out.write(str.getBytes(StandardCharsets.UTF_8))
  }

  implicit val byteArray: DataEncoder[Array[Byte]] = instance(BinaryType) {
    (out, bytes) =>
      out.writeInt(bytes.length)
      out.write(bytes)
  }

  implicit val byteBuffer: DataEncoder[ByteBuffer] = instance(BinaryType) {
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

  implicit def array[A](implicit encodeA: DataEncoder[A]): DataEncoder[Array[A]] = sizedInstance[Array[A]](ArrayType(encodeA.dataType), arr => arr.length * encodeA.dataType.size) {
    (output, arr) =>
      output.writeInt(arr.length)
      var i = 0
      while (i < arr.length) {
        encodeA.encode(output, arr(i))
        i += 1
      }
  }

  implicit def traversable[F[X] <: GenTraversable[X], A](implicit encodeA: DataEncoder[A]): DataEncoder[F[A]] = sizedInstance[F[A]](ArrayType(encodeA.dataType), seq => seq.size * encodeA.dataType.size) {
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
    def writeUTF(s: String): Unit = {
      // the logic for this is actually quite complex... it's all in static function [[java.io.DataOutputStream.writeUTF]], but that's package private.
      // Nobody ought to be using this anyway, though, because it's not how we write strings. So we'll just do it inefficiently by going through a byte array.
      import java.io.{DataOutputStream, ByteArrayOutputStream}
      val baos = new ByteArrayOutputStream(s.length)
      val dos = new DataOutputStream(baos)
      try {
        dos.writeUTF(s)
        buf.put(baos.toByteArray)
      } finally {
        dos.close()
      }
    }
  }

  def writeSized[T](value: T)(implicit dataEncoder: DataEncoder[T]): ByteBuffer = dataEncoder.sizeOf(value) match {
    case size if size >= 0 =>
      val buf = ByteBuffer.allocate(size)
      dataEncoder.encode(new BufferOutput(buf), value)
      buf.rewind()
      buf
    case _ =>
      import java.io.{DataOutputStream, ByteArrayOutputStream}
      val baos = new ByteArrayOutputStream()
      val dos = new DataOutputStream(baos)
      try {
        dataEncoder.encode(dos, value)
        ByteBuffer.wrap(baos.toByteArray)
      } finally {
        baos.close()
      }
  }

  def combineSize(a: Int, b: Int): Int = if (a >= 0 && b >= 0) a + b else -1
}

private[runtime] sealed trait DataEncoderDerivations { self: DataEncoder.type =>

  class StructDataEncoder[T](
    val dataType: StructType,
    encodeFn: (DataOutput, T) => Unit,
    sizeFn: T => Int
  ) extends DataEncoder[T] {
    override def encode(dataOutput: DataOutput, value: T): Unit = encodeFn(dataOutput, value)
    def sizeOf(t: T): Int = sizeFn(t)
  }

  object StructDataEncoder {
    def instance[T](dataType: StructType)(encodeFn: (DataOutput, T) => Unit)(sizeFn: T => Int): StructDataEncoder[T] = new StructDataEncoder(dataType, encodeFn, sizeFn)

    implicit val hnil: StructDataEncoder[HNil] = instance[HNil](StructType(Nil))((_, _) => ())(_ => 0)

    implicit def hcons[K <: Symbol, H, T <: HList](implicit
      label: Witness.Aux[K],
      encoderH: DataEncoder[H],
      encoderT: StructDataEncoder[T]
    ): StructDataEncoder[FieldType[K, H] :: T] =
      instance[FieldType[K, H] :: T](StructType(StructField(label.value.name, encoderH.dataType) :: encoderT.dataType.fields)) {
        (output, ht) =>
          encoderT.encode(encoderH.encodeAnd(output, ht.head), ht.tail)
      } {
        ht => combineSize(encoderH.sizeOf(ht.head), encoderT.sizeOf(ht.tail))
      }

    implicit def caseClass[A <: Product, L <: HList](implicit gen: LabelledGeneric.Aux[A, L], encoderL: Lazy[StructDataEncoder[L]]): StructDataEncoder[A] =
      instance[A](encoderL.value.dataType)((output, a) => encoderL.value.encode(output, gen.to(a)))(a => encoderL.value.sizeOf(gen.to(a)))
  }

  implicit def fromStructDataEncoder[T](implicit structDataEncoderT: StructDataEncoder[T]): DataEncoder[T] = structDataEncoderT
}
