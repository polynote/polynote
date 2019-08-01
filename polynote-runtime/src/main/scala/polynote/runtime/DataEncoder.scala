package polynote.runtime

import java.io.DataOutput
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import shapeless.labelled.FieldType
import shapeless.{::, Generic, HList, HNil, LabelledGeneric, Lazy, Witness}

import scala.collection.GenTraversable
import polynote.runtime.macros.StructDataEncoderMacros

trait DataEncoder[@specialized T] extends Serializable {
  final type In = T
  def encode(dataOutput: DataOutput, value: T): Unit
  def encodeAnd(dataOutput: DataOutput, value: T): DataOutput = {
    encode(dataOutput, value)
    dataOutput
  }
  def dataType: DataType
  def sizeOf(t: T): Int
  def numeric: Option[Numeric[T]]
}

object DataEncoder extends DataEncoderDerivations {

  def sizedInstance[@specialized T](typ: DataType, size: T => Int, numericOpt: Option[Numeric[T]] = None)(fn: (DataOutput, T) => Unit): DataEncoder[T] = new DataEncoder[T] {
    def encode(dataOutput: DataOutput, value: T): Unit = fn(dataOutput, value)
    val dataType: DataType = typ
    override def sizeOf(t: T): Int = size(t)
    val numeric: Option[Numeric[T]] = numericOpt
  }

  def instance[@specialized T](typ: DataType)(fn: (DataOutput, T) => Unit): DataEncoder[T] = sizedInstance[T](typ, _ => typ.size)(fn)

  def numericInstance[@specialized T : Numeric](typ: DataType)(fn: (DataOutput, T) => Unit): DataEncoder[T] =
    sizedInstance(typ, (_: T) => typ.size, Some(implicitly[Numeric[T]]))(fn)

  implicit val byte: DataEncoder[Byte] = instance(ByteType)(_ writeByte _)
  implicit val boolean: DataEncoder[Boolean] = instance(BoolType)(_ writeBoolean _)
  implicit val short: DataEncoder[Short] = numericInstance(ShortType)(_ writeShort _)
  implicit val int: DataEncoder[Int] = numericInstance(IntType)(_ writeInt _)
  implicit val long: DataEncoder[Long] = numericInstance(LongType)(_ writeLong _)
  implicit val float: DataEncoder[Float] = numericInstance(FloatType)(_ writeFloat _)
  implicit val double: DataEncoder[Double] = numericInstance(DoubleType)(_ writeDouble _)
  implicit val string: DataEncoder[String] = instance(StringType) {
    (out, str) =>
      out.writeInt(str.length)
      out.write(str.getBytes(StandardCharsets.UTF_8))
  }

  // NOT implicit!
  def unknownDataEncoder[T](typeName: String): DataEncoder[T] = instance[T](TypeType) {
    (out, _) =>
      val unknownMessage = s"Missing DataRepr for type $typeName"
      out.writeInt(unknownMessage.length)
      out.write(unknownMessage.getBytes(StandardCharsets.UTF_8))
  }

  implicit val byteArray: DataEncoder[Array[Byte]] = sizedInstance[Array[Byte]](BinaryType, arr => arr.length + 4) {
    (out, bytes) =>
      out.writeInt(bytes.length)
      out.write(bytes)
  }

  implicit val byteBuffer: DataEncoder[ByteBuffer] = sizedInstance[ByteBuffer](BinaryType, buf => buf.limit() + 4) {
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

  implicit def map[F[KK, VV] <: Map[KK, VV], K, V](implicit structEncoder: DataEncoder.StructDataEncoder[(K, V)]): DataEncoder[Map[K, V]] = sizedInstance[Map[K, V]](
    MapType(structEncoder.dataType),
    map => map.size * structEncoder.dataType.size) {
    (output, map) =>
      output.writeInt(map.size)
      map.foreach(structEncoder.encode(output, _))
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

  abstract class StructDataEncoder[T](
    val dataType: StructType
  ) extends DataEncoder[T] {
    val numeric: Option[Numeric[T]] = None
    def field(name: String): Option[(T => Any, DataEncoder[_])]
  }

  object StructDataEncoder {

    implicit val hnil: StructDataEncoder[HNil] = new StructDataEncoder[HNil](StructType(Nil)) {
      def encode(dataOutput: DataOutput, value: HNil): Unit = ()
      def sizeOf(t: HNil): Int = 0
      def field(name: String): Option[(HNil => Any, DataEncoder[_])] = None
    }

    implicit def hcons[K <: Symbol, H, T <: HList](implicit
      label: Witness.Aux[K],
      encoderH: DataEncoder[H],
      encoderT: StructDataEncoder[T]
    ): StructDataEncoder[FieldType[K, H] :: T] =
      new StructDataEncoder[FieldType[K, H] :: T](StructType(StructField(label.value.name, encoderH.dataType) :: encoderT.dataType.fields)) {
        val fieldName: String = label.value.name
        def encode(output: DataOutput, value: FieldType[K, H] :: T): Unit = encoderT.encode(encoderH.encodeAnd(output, value.head), value.tail)
        def sizeOf(value: FieldType[K, H] :: T): Int = combineSize(encoderH.sizeOf(value.head), encoderT.sizeOf(value.tail))

        // Note: the returned getter here is very slow.
        def field(name: String): Option[((FieldType[K, H] :: T) => Any, DataEncoder[_])] = if (name == fieldName) {
          val getter: (FieldType[K, H] :: T) => Any = ht => ht.head
          Some(getter -> encoderH)
        } else {
          encoderT.field(name).map {
            case (getterT, enc) =>
              val getter: (FieldType[K, H] :: T) => Any = getterT.compose(_.tail)
              getter -> enc
          }
        }
      }

    implicit def caseClassMacro[A <: Product]: StructDataEncoder[A] = macro StructDataEncoderMacros.materialize[A]

  }

  trait LowPriorityStructDataEncoder { self: StructDataEncoder.type =>

    // This is lower priority, so that the macro (which is specialized and voids O(N) field access overhead) can be preferred
    implicit def caseClass[A <: Product, L <: HList](implicit gen: LabelledGeneric.Aux[A, L], encoderL: Lazy[StructDataEncoder[L]]): StructDataEncoder[A] =
      new StructDataEncoder[A](encoderL.value.dataType) {
        def encode(output: DataOutput, a: A): Unit = encoderL.value.encode(output, gen.to(a))
        def sizeOf(a: A): Int = encoderL.value.sizeOf(gen.to(a))
        def field(name: String): Option[(A => Any, DataEncoder[_])] = encoderL.value.field(name).map {
          case (getter, enc) => getter.compose(gen.to) -> enc
        }
      }
  }

  implicit def fromStructDataEncoder[T](implicit structDataEncoderT: StructDataEncoder[T]): DataEncoder[T] = structDataEncoderT
}
