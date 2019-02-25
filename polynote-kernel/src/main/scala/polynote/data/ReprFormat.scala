package polynote.data

import scodec.bits.{BitVector, ByteVector}
import shapeless.{Generic, HList, HNil, Lazy, ::}

import scala.collection.GenTraversable

trait ReprFormat[T] extends (T => ByteVector)

object ReprFormat extends ReprFormat0 {

  final case class instance[T](fn: T => ByteVector) extends ReprFormat[T] {
    def apply(t: T): ByteVector = fn(t)
  }

  private val one = ByteVector.fromByte(1)
  private val zero = ByteVector.fromByte(0)

  implicit val byteFormat: ReprFormat[Byte] = instance(ByteVector.fromByte)
  implicit val boolFormat: ReprFormat[Boolean] = instance(b => if (b) one else zero)
  implicit val shortFormat: ReprFormat[Short] = instance(ByteVector.fromShort(_))
  implicit val intFormat: ReprFormat[Int] = instance(ByteVector.fromInt(_))
  implicit val longFormat: ReprFormat[Long] = instance(ByteVector.fromLong(_))
  implicit val floatFormat: ReprFormat[Float] = instance(f => ByteVector.fromInt(java.lang.Float.floatToIntBits(f)))
  implicit val doubleFormat: ReprFormat[Double] = instance(d => ByteVector.fromLong(java.lang.Double.doubleToLongBits(d)))
  implicit val stringFormat: ReprFormat[String] = instance(str => ByteVector.encodeUtf8(str).fold(throw _, identity))
  implicit val byteArrayFormat: ReprFormat[Array[Byte]] = instance(ByteVector(_))
  implicit val byteVectorFormat: ReprFormat[ByteVector] = instance(identity)
  implicit val bitVectorFormat: ReprFormat[BitVector] = instance(_.toByteVector)

  implicit def arrayFormat[A](implicit elFormat: ReprFormat[A]): ReprFormat[Array[A]] = instance {
    arr => arr.foldLeft(ByteVector.fromInt(arr.length)) {
      (accum: ByteVector, next: A) => accum ++ elFormat(next)
    }
  }

  implicit def traversableFormat[F[X] <: GenTraversable[X], A](implicit elFormat: ReprFormat[A]): ReprFormat[F[A]] = instance {
    arr => arr.foldLeft(ByteVector.fromInt(arr.size)) {
      (accum, next) => accum ++ elFormat(next)
    }
  }

  implicit def optionalFormat[A](implicit elFormat: ReprFormat[A]): ReprFormat[Option[A]] = instance {
    case Some(el) => one ++ elFormat(el)
    case None => zero
  }

  implicit val hnilFormat: ReprFormat[HNil] = instance(_ => ByteVector.empty)

  implicit def hlistFormat[H, T <: HList](implicit formatH: ReprFormat[H], formatT: ReprFormat[T]): ReprFormat[H :: T] =
    instance {
      case h :: t => formatH(h) ++ formatT(t)
    }


}

private[data] trait ReprFormat0 { self: ReprFormat.type =>
  implicit def struct[A, L <: HList](implicit gen: Generic.Aux[A, L], formatL: Lazy[ReprFormat[L]]): ReprFormat[A] =
    instance(a => formatL.value(gen.to(a)))
}