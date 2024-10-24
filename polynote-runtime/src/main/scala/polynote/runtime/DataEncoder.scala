package polynote.runtime

import java.io.DataOutput
import java.nio.{Buffer, ByteBuffer}
import java.nio.charset.StandardCharsets
import scala.collection.GenTraversable
import polynote.runtime.macros.StructDataEncoderMacros
import polynote.runtime.util.stringPrefix

trait DataEncoder[@specialized T] extends Serializable {
  final type In = T
  def encode(dataOutput: DataOutput, value: T): Unit
  def encodeAnd(dataOutput: DataOutput, value: T): DataOutput = {
    encode(dataOutput, value)
    dataOutput
  }
  def encodeDisplayString(value: T): String = DataEncoder.truncateString(value.toString)
  def dataType: DataType
  def sizeOf(t: T): Int
  def numeric: Option[Numeric[T]]

  def bimap[U](from: U => T, to: T => U): DataEncoder[U] = new DataEncoder[U] {
    override def encode(dataOutput: DataOutput, value: U): Unit = DataEncoder.this.encode(dataOutput, from(value))
    override val dataType: DataType = DataEncoder.this.dataType
    override def sizeOf(t: U): Int = DataEncoder.this.sizeOf(from(t))
    override def numeric: Option[Numeric[U]] = DataEncoder.this.numeric.map {
      numericT => new Numeric[U] {
        override def plus(x: U, y: U): U = to(numericT.plus(from(x), from(y)))
        override def minus(x: U, y: U): U = to(numericT.minus(from(x), from(y)))
        override def times(x: U, y: U): U = to(numericT.times(from(x), from(y)))
        override def negate(x: U): U = to(numericT.negate(from(x)))
        override def fromInt(x: Int): U = to(numericT.fromInt(x))
        override def toInt(x: U): Int = numericT.toInt(from(x))
        override def toLong(x: U): Long = numericT.toLong(from(x))
        override def toFloat(x: U): Float = numericT.toFloat(from(x))
        override def toDouble(x: U): Double = numericT.toDouble(from(x))
        override def compare(x: U, y: U): Int = numericT.compare(from(x), from(y))
        def parseString(str: String): Option[U] = None  // was added in 2.13, have to implement
      }
    }
  }

  /**
    * Contramap this data encoder, discarding any numeric abilities
    */
  def contramap[U](from: U => T): DataEncoder[U] = new DataEncoder[U] {
    override def encode(dataOutput: DataOutput, value: U): Unit = DataEncoder.this.encode(dataOutput, from(value))
    override val dataType: DataType = DataEncoder.this.dataType
    override def sizeOf(t: U): Int = DataEncoder.this.sizeOf(from(t))
    override val numeric: Option[Numeric[U]] = None
  }
}

object DataEncoder extends DataEncoder0 {
  def truncateString(str: String): String = if (str.length > 255) str.substring(0, 254) + "…" else str
  def formatCollection[T](coll: GenTraversable[T], formatItem: T => String, prefix: Option[String]): String = {
    val innerStrs = if (coll.size > 10) {
      coll.take(10).toSeq.map(formatItem).map(_.linesWithSeparators.map("  " + _).mkString) :+ s"  …(${coll.size - 10} more elements)"
    } else coll.map(formatItem)

    prefix.getOrElse(stringPrefix(coll)) + "(\n" + innerStrs.mkString(",\n") + "\n)"
  }

  def formatCollection[T](coll: GenTraversable[T], formatItem: T => String, prefix: String): String =
    formatCollection(coll, formatItem, Some(prefix))

  def formatCollection[T](coll: GenTraversable[T], formatItem: T => String): String =
    formatCollection(coll, formatItem, None)

  def formatCollection[T](coll: GenTraversable[T], prefix: String)(implicit enc: DataEncoder[T]): String =
    formatCollection(coll, t => enc.encodeDisplayString(t), Some(prefix))

  def formatCollection[T](coll: GenTraversable[T])(implicit enc: DataEncoder[T]): String =
    formatCollection(coll, t => enc.encodeDisplayString(t), None)

  class SizedEncoder[@specialized T](
    typ: DataType,
    size: T => Int,
    numericOpt: Option[Numeric[T]] = None)(
    fn: (DataOutput, T) => Unit
  ) extends DataEncoder[T] {
    def encode(dataOutput: DataOutput, value: T): Unit = fn(dataOutput, value)
    val dataType: DataType = typ
    override def sizeOf(t: T): Int = size(t)
    val numeric: Option[Numeric[T]] = numericOpt
  }

  def sizedInstance[@specialized T](typ: DataType, size: T => Int, numericOpt: Option[Numeric[T]] = None)(fn: (DataOutput, T) => Unit): DataEncoder[T] =
    new SizedEncoder[T](typ, size, numericOpt)(fn)

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
      if (str == null) {
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
      (b:Buffer).rewind()
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

  implicit def array[A](implicit encodeA: DataEncoder[A]): DataEncoder[Array[A]] = new SizedEncoder[Array[A]](ArrayType(encodeA.dataType), arr => combineSize(seqSize(arr, encodeA), 4))({
    (output, arr) =>
      output.writeInt(arr.length)
      var i = 0
      while (i < arr.length) {
        encodeA.encode(output, arr(i))
        i += 1
      }
  }) {
    override def encodeDisplayString(value: Array[A]): String = formatCollection(value, "Array")
  }

  implicit def optional[A](implicit encodeA: DataEncoder[A]): DataEncoder[Option[A]] = new SizedEncoder[Option[A]](OptionalType(encodeA.dataType), opt => opt.fold(1)(a => combineSize(encodeA.sizeOf(a), 1)))({
    case (output, None) =>
      output.writeBoolean(false)
    case (output, Some(a)) =>
      output.writeBoolean(true)
      encodeA.encode(output, a)
  }) {
    override def encodeDisplayString(value: Option[A]): String = value match {
      case Some(elem) => s"Some(${encodeA.encodeDisplayString(elem)})"
      case None       => "None"
    }
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
      (buf:Buffer).rewind()
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

    override def encodeDisplayString(value: F[K, V]): String = formatCollection[(K, V)](
      value,
      (tup: (K, V)) => s"${encodeK.encodeDisplayString(tup._1)} -> ${encodeV.encodeDisplayString(tup._2)}",
      stringPrefix(value)
    )
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
    implicit def caseClassMacro[A <: Product]: StructDataEncoder[A] = macro StructDataEncoderMacros.materialize[A]

    // Make a struct encoder that wraps a value with its index
    def forScalar[A](implicit encoder: DataEncoder[A]): StructDataEncoder[(Int, A)] = new StructDataEncoder[(Int, A)](StructType(List(
      StructField("index", IntType), StructField("value", encoder.dataType)
    ))) {
      override def field(name: String): Option[(((Int, A)) => Any, DataEncoder[_])] = name match {
        case "index" => Some((_._1, int))
        case "value" => Some((_._2, encoder))
        case _ => None
      }

      override def encode(dataOutput: DataOutput, value: (Int, A)): Unit = {
        dataOutput.writeInt(value._1)
        encoder.encode(dataOutput, value._2)
      }

      override def sizeOf(t: (Int, A)): Int = encoder.sizeOf(t._2) + 4

      override def encodeDisplayString(value: (Int, A)): String = s"(${value._1}, ${encoder.encodeDisplayString(value._2)})"
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

    override def encodeDisplayString(value: F[A]): String = formatCollection[A](value)(encodeA)
  }

  implicit def seq[A](implicit encodeA: DataEncoder[A]): DataEncoder[Seq[A]] = new SeqEncoder(encodeA)
  implicit def list[A](implicit encodeA: DataEncoder[A]): DataEncoder[List[A]] = new SeqEncoder(encodeA)
  implicit def vec[A](implicit encodeA: DataEncoder[A]): DataEncoder[Vector[A]] = new SeqEncoder(encodeA)
}
