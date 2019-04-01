package polynote.runtime

import java.io.{ByteArrayOutputStream, DataOutput, DataOutputStream}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import shapeless.HNil

import scala.collection.GenSeq
import scala.concurrent.Future

trait ReprsOf[T] extends Serializable {
  def apply(value: T): Array[ValueRepr]
}

object ReprsOf extends ExpandedScopeReprs {

  def instance[T](reprs: T => Array[ValueRepr]): ReprsOf[T] = new ReprsOf[T] {
    def apply(value: T): Array[ValueRepr] = reprs(value)
  }

  class DataReprsOf[T](val dataType: DataType, val encode: T => ByteBuffer) extends ReprsOf[T] {
    def apply(t: T): Array[ValueRepr] = Array(DataRepr(dataType, encode(t)))
  }

  object DataReprsOf {
    def apply[T](dataType: DataType)(encode: T => ByteBuffer): DataReprsOf[T] = new DataReprsOf(dataType, encode)

    implicit val byte: DataReprsOf[Byte] = DataReprsOf(ByteType)(byte => ByteBuffer.wrap(Array(byte)))
    implicit val boolean: DataReprsOf[Boolean] = DataReprsOf(BoolType)(bool => ByteBuffer.wrap(Array(if (bool) 1.toByte else 0.toByte)))
    implicit val short: DataReprsOf[Short] = DataReprsOf(ShortType)(short => ByteBuffer.wrap(Array((0xFF & (short >> 8)).toByte, (0xFF & short).toByte)))
    implicit val int: DataReprsOf[Int] = DataReprsOf(IntType)(intToBuf)
    implicit val long: DataReprsOf[Long] = DataReprsOf(LongType)(longToBuf)
    implicit val float: DataReprsOf[Float] = DataReprsOf(FloatType)(f => intToBuf(java.lang.Float.floatToIntBits(f)))
    implicit val double: DataReprsOf[Double] = DataReprsOf(DoubleType)(d => longToBuf(java.lang.Double.doubleToLongBits(d)))
    implicit val string: DataReprsOf[String] = DataReprsOf(StringType) {
      str =>
        val bytes = str.getBytes(StandardCharsets.UTF_8)
        val buf = ByteBuffer.allocate(bytes.length + 4)
        buf.putInt(bytes.length)
        buf.put(bytes)
        buf
    }

    implicit val byteArray: DataReprsOf[Array[Byte]] = DataReprsOf(BinaryType)(ByteBuffer.wrap)

    implicit def fromDataEncoder[T](implicit dataEncoder: DataEncoder[T]): DataReprsOf[T] = DataReprsOf(dataEncoder.dataType) {
      t => DataEncoder.writeSized(t)
    }

  }

  private[runtime] trait ExpandedScopeDataReprs { self: DataReprsOf.type =>
    implicit def expanded[T]: DataReprsOf[T] = macro macros.ExpandedScopeMacros.resolveFromScope
  }

  @inline private def intToBuf(int: Int) =
    ByteBuffer.wrap(Array((0xFF & (int >> 24)).toByte, (0xFF & (int >> 16)).toByte, (0xFF & (int >> 8)).toByte, (0xFF & int).toByte))

  @inline private def longToBuf(long: Long) =
    ByteBuffer.wrap(
      Array(
        (0xFF & (long >> 56)).toByte, (0xFF & (long >> 48)).toByte, (0xFF & (long >> 40)).toByte, (0xFF & (long >> 32)).toByte,
        (0xFF & (long >> 24)).toByte, (0xFF & (long >> 16)).toByte, (0xFF & (long >> 8)).toByte, (0xFF & long).toByte))

  implicit def fromDataReprs[T](implicit dataReprsOfT: DataReprsOf[T]): ReprsOf[T] = dataReprsOfT

  val empty: ReprsOf[Any] = instance(_ => Array.empty)

}

private[runtime] trait CollectionReprs { self: ReprsOf.type =>

  implicit def seq[F[X] <: GenSeq[X], A](implicit dataReprsOfA: DataReprsOf[A]): ReprsOf[F[A]] = instance {
    seq => Array(StreamingDataRepr(dataReprsOfA.dataType, seq.size, seq.iterator.map(dataReprsOfA.encode)))
  }

  implicit def array[A](implicit dataReprsOfA: DataReprsOf[A]): ReprsOf[Array[A]] = instance {
    arr => Array(StreamingDataRepr(dataReprsOfA.dataType, arr.length, arr.iterator.map(dataReprsOfA.encode)))
  }

  implicit def future[A](implicit dataReprsOfA: DataReprsOf[A]): ReprsOf[Future[A]] = instance {
    fut =>
      val repr = UpdatingDataRepr(dataReprsOfA.dataType)
      fut.onSuccess {
        case a => repr.tryUpdate(dataReprsOfA.encode(a))
      }(scala.concurrent.ExecutionContext.global)
      Array(repr)
  }

}

private[runtime] trait ExpandedScopeReprs { self: ReprsOf.type =>

  implicit def expanded[T]: ReprsOf[T] = macro macros.ExpandedScopeMacros.resolveFromScope

}
