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

object DataEncoder extends DataEncoder0 {

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
  implicit val string: DataEncoder[String] = sizedInstance[String](StringType, str => if (str == null) 4 else str.getBytes(StandardCharsets.UTF_8).length + 4) {
    (out, str) =>
      if (out == null) {
        out.writeInt(-1)
      } else {
        val bytes = str.getBytes(StandardCharsets.UTF_8)
        out.writeInt(bytes.length)
        out.write(bytes)
      }
  }

  // NOT implicit!
  def unknownDataEncoder[T](typeName: String): DataEncoder[T] = {
    val msg = s"Missing DataRepr for type $typeName".getBytes(StandardCharsets.UTF_8)
    sizedInstance[T](TypeType, _ => msg.length + 4) {
      (out, _) =>
        out.writeInt(msg.length)
        out.write(msg)
    }
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

  private[runtime] def seqSize[A](arr: GenTraversable[A], encoder: DataEncoder[A]): Int =
    if (encoder.dataType.size >= 0) {
      encoder.dataType.size * arr.size
    } else try {
      var s = 0L
      val iter = arr.toIterator
      while (iter.hasNext) {
        s += encoder.sizeOf(iter.next())
        if (s > Int.MaxValue) {
          return -1;
        }
      }
      s.toInt
    } catch {
      case err: Throwable => -1
    }

  implicit def array[A](implicit encodeA: DataEncoder[A]): DataEncoder[Array[A]] = sizedInstance[Array[A]](ArrayType(encodeA.dataType), arr => combineSize(seqSize(arr, encodeA), 4)) {
    (output, arr) =>
      output.writeInt(arr.length)
      var i = 0
      while (i < arr.length) {
        encodeA.encode(output, arr(i))
        i += 1
      }
  }

  implicit def optional[A](implicit encodeA: DataEncoder[A]): DataEncoder[Option[A]] = sizedInstance[Option[A]](OptionalType(encodeA.dataType), opt => opt.fold(1)(a => combineSize(encodeA.sizeOf(a), 1))) {
    case (output, None) =>
      output.writeBoolean(false)
    case (output, Some(a)) =>
      output.writeBoolean(true)
      encodeA.encode(output, a)
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

  def writeSized[T](value: T)(implicit dataEncoder: DataEncoder[T]): ByteBuffer = writeSized(value, dataEncoder.dataType.size)
  def writeSized[T](value: T, size: Int)(implicit dataEncoder: DataEncoder[T]): ByteBuffer = size match {
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
  def multiplySize(size: Int, count: Int): Int = if (size >= 0 && count >= 0) {
    (size.toLong * count) match {
      case s if s <= Int.MaxValue => s.toInt
      case _ => -1
    }
  } else -1
}

private[runtime] sealed trait DataEncoder0 extends DataEncoderDerivations { self: DataEncoder.type =>

  class MapDataEncoder[F[A, B] <: scala.collection.GenMap[A, B], K, V](encodeK: DataEncoder[K], encodeV: DataEncoder[V]) extends DataEncoder[F[K, V]] {
    def encode(output: DataOutput, map: F[K, V]): Unit = {
      output.writeInt(map.size)
      map.foreach {
        case (k, v) =>
          encodeK.encode(output, k)
          encodeV.encode(output, v)
      }
    }

    def dataType: DataType = MapType(encodeK.dataType, encodeV.dataType)

    def sizeOf(map: F[K, V]): Int = if (encodeK.dataType.size >= 0 && encodeV.dataType.size >= 0) {
      (4L + map.size.toLong * (encodeK.dataType.size + encodeV.dataType.size)) match {
        case s if s <= Int.MaxValue => s.toInt
        case s => -1
      }
    } else try {
      var s = 4L
      val iter = map.iterator
      while (iter.hasNext) {
        val (k, v) = iter.next()
        s += encodeK.sizeOf(k) + encodeV.sizeOf(v)
        if (s > Int.MaxValue) {
          return -1
        }
      }
      s.toInt
    } catch {
      case err: Throwable => -1
    }

    def numeric: Option[Numeric[F[K, V]]] = None
  }


  implicit def mapOfStruct[K, V](implicit encodeK: DataEncoder[K], encodeV: StructDataEncoder[V]): DataEncoder[Map[K, V]] = new MapDataEncoder(encodeK, encodeV)

}

private[runtime] sealed trait DataEncoderDerivations { self: DataEncoder.type =>


  implicit def predefMap[K, V](implicit encodeK: DataEncoder[K], encodeV: DataEncoder[V]): DataEncoder[Map[K, V]] = new MapDataEncoder(encodeK, encodeV)
  implicit def collectionMap[K, V](implicit encodeK: DataEncoder[K], encodeV: DataEncoder[V]): DataEncoder[scala.collection.Map[K, V]] = new MapDataEncoder(encodeK, encodeV)

  abstract class StructDataEncoder[T](
    val dataType: StructType
  ) extends DataEncoder[T] {
    val numeric: Option[Numeric[T]] = None
    def field(name: String): Option[(T => Any, DataEncoder[_])]
  }

  object StructDataEncoder {

//    implicit val hnil: StructDataEncoder[HNil] = new StructDataEncoder[HNil](StructType(Nil)) {
//      def encode(dataOutput: DataOutput, value: HNil): Unit = ()
//      def sizeOf(t: HNil): Int = 0
//      def field(name: String): Option[(HNil => Any, DataEncoder[_])] = None
//    }
//
//    implicit def hcons[K <: Symbol, H, T <: HList](implicit
//      label: Witness.Aux[K],
//      encoderH: DataEncoder[H],
//      encoderT: StructDataEncoder[T]
//    ): StructDataEncoder[FieldType[K, H] :: T] =
//      new StructDataEncoder[FieldType[K, H] :: T](StructType(StructField(label.value.name, encoderH.dataType) :: encoderT.dataType.fields)) {
//        val fieldName: String = label.value.name
//        def encode(output: DataOutput, value: FieldType[K, H] :: T): Unit = encoderT.encode(encoderH.encodeAnd(output, value.head), value.tail)
//        def sizeOf(value: FieldType[K, H] :: T): Int = combineSize(encoderH.sizeOf(value.head), encoderT.sizeOf(value.tail))
//
//        // Note: the returned getter here is very slow.
//        def field(name: String): Option[((FieldType[K, H] :: T) => Any, DataEncoder[_])] = if (name == fieldName) {
//          val getter: (FieldType[K, H] :: T) => Any = ht => ht.head
//          Some(getter -> encoderH)
//        } else {
//          encoderT.field(name).map {
//            case (getterT, enc) =>
//              val getter: (FieldType[K, H] :: T) => Any = getterT.compose(_.tail)
//              getter -> enc
//          }
//        }
//      }

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

  class SeqEncoder[F[X] <: scala.collection.GenSeq[X], A](encodeA: DataEncoder[A]) extends DataEncoder[F[A]] {
    def encode(output: DataOutput, seq: F[A]): Unit = {
      output.writeInt(seq.size)
      seq.foreach(encodeA.encode(output, _))
    }
    def dataType: DataType = ArrayType(encodeA.dataType)
    def sizeOf(t: F[A]): Int = if (encodeA.dataType.size >= 0) (encodeA.dataType.size * t.size + 4) else seqSize[A](t, encodeA) + 4
    def numeric: Option[Numeric[F[A]]] = None
  }

  implicit def seq[A](implicit encodeA: DataEncoder[A]): DataEncoder[Seq[A]] = new SeqEncoder(encodeA)
  implicit def list[A](implicit encodeA: DataEncoder[A]): DataEncoder[List[A]] = new SeqEncoder(encodeA)
  implicit def vec[A](implicit encodeA: DataEncoder[A]): DataEncoder[Vector[A]] = new SeqEncoder(encodeA)
}
