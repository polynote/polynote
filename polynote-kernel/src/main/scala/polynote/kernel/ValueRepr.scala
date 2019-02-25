package polynote.kernel

import polynote.runtime._
import scodec.{Codec, codecs}
import codecs.{Discriminated, Discriminator, byte}
import scodec.bits.ByteVector
import scodec.codecs.implicits.{implicitByteVectorCodec => _, _}
import shapeless.cachedImplicit

abstract class ValueReprCompanion[T](msgId: Byte) {
  implicit val discriminator: Discriminator[ValueRepr, T, Byte] = Discriminator(msgId)
}

object DataTypeCodec {

  implicit val byteDiscriminator: Discriminator[DataType, ByteType.type, Byte] = Discriminator(0)
  implicit val boolDiscriminator: Discriminator[DataType, BoolType.type, Byte] = Discriminator(1)
  implicit val shortDiscriminator: Discriminator[DataType, ShortType.type, Byte] = Discriminator(2)
  implicit val intDiscriminator: Discriminator[DataType, IntType.type, Byte] = Discriminator(3)
  implicit val longDiscriminator: Discriminator[DataType, LongType.type, Byte] = Discriminator(4)
  implicit val floatDiscriminator: Discriminator[DataType, FloatType.type, Byte] = Discriminator(5)
  implicit val doubleDiscriminator: Discriminator[DataType, DoubleType.type, Byte] = Discriminator(6)
  implicit val binaryDiscriminator: Discriminator[DataType, BinaryType.type, Byte] = Discriminator(7)
  implicit val stringDiscriminator: Discriminator[DataType, StringType.type, Byte] = Discriminator(8)
  implicit val structDiscriminator: Discriminator[DataType, StructType, Byte] = Discriminator(9)
  implicit val optionalDiscriminator: Discriminator[DataType, OptionalType, Byte] = Discriminator(10)
  implicit val arrayDiscriminator: Discriminator[DataType, ArrayType, Byte] = Discriminator(11)

  implicit val discriminated: Discriminated[DataType, Byte] = Discriminated(byte)

  implicit val dataTypeCodec: Codec[DataType] = cachedImplicit
}

/**
  * A plain text representation of a value
  */
final case class StringRepr(string: String) extends ValueRepr
object StringRepr extends ValueReprCompanion[StringRepr](0)

/**
  * An HTML representation of a value
  */
final case class HTMLRepr(html: String) extends ValueRepr
object HTMLRepr extends ValueReprCompanion[HTMLRepr](1)

/**
  * A binary representation of a value, encoded in Polynote's format (TODO)
  */
final case class DataRepr(dataType: DataType, data: ByteVector) extends ValueRepr
object DataRepr extends ValueReprCompanion[DataRepr](2)

/**
  * An identifier for a "lazy" value. It won't be evaluated until the client asks for it by its handle.
  * We use Ints for the handle, because JS needs to store the handle, and it can't store Long. We just can't have
  * more than 2^32^ different handles... I think there would be bigger issues at that point.
  */
final case class LazyDataRepr(handle: Int, dataType: DataType) extends ValueRepr
object LazyDataRepr extends ValueReprCompanion[LazyDataRepr](3)

/**
  * An identifier for an "updating" value. The server may push one or more binary representations of it over time, and
  * when there won't be anymore updates, the server will push a message indicating this.
  */
final case class UpdatingDataRepr(handle: Int, dataType: DataType) extends ValueRepr
object UpdatingDataRepr extends ValueReprCompanion[UpdatingDataRepr](4)

/**
  * An identifier for a "streaming" value. The server will push zero or more binary representations over time, and when
  * there are no more values, the server will push a message indicating this. In contrast to [[UpdatingDataRepr]],
  * each value pushed is considered part of a collection of values.
  *
  * The server will only push values when requested to do so by the client – the client can either pull chunks of values,
  * or it can ask the server to push all values as they become available.
  */
final case class StreamingDataRepr(handle: Int, dataType: DataType, knownSize: Option[Int]) extends ValueRepr
object StreamingDataRepr extends ValueReprCompanion[StreamingDataRepr](5)

sealed trait ValueRepr
object ValueRepr {
  import DataTypeCodec.dataTypeCodec
  implicit val discriminated: Discriminated[ValueRepr, Byte] = Discriminated(byte)

  // we want to use 32-bit lengths for byte vectors
  implicit val byteVectorCodec: Codec[ByteVector] = codecs.variableSizeBytesLong(codecs.uint32, codecs.bytes)
  implicit val codec: Codec[ValueRepr] = cachedImplicit
}


