package polynote.kernel

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import polynote.runtime._
import scodec.Codec
import scodec.bits.ByteVector
import scodec.codecs._
import scodec.codecs.implicits._
import shapeless.cachedImplicit

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
  //implicit val dateDiscriminator: Discriminator[DataType, DateType.type, Byte] = Discriminator(12)
  //implicit val timestampDiscriminator: Discriminator[DataType, TimestampType.type, Byte] = Discriminator(13)
  implicit val typeDiscriminator: Discriminator[DataType, TypeType.type, Byte] = Discriminator(14)
  implicit val mapDiscriminator: Discriminator[DataType, MapType, Byte] = Discriminator(15)

  implicit val dataTypeDiscriminated: Discriminated[DataType, Byte] = Discriminated(byte)

  implicit val dataTypeCodec: Codec[DataType] = cachedImplicit
}

object ValueReprCodec {
  import DataTypeCodec.dataTypeCodec

  implicit val valueReprDiscriminated: Discriminated[ValueRepr, Byte] = Discriminated(byte)
  implicit val stringRepr: Discriminator[ValueRepr, StringRepr, Byte] = Discriminator(0)
  implicit val mimeRepr: Discriminator[ValueRepr, MIMERepr, Byte] =  Discriminator(1)
  implicit val dataRepr: Discriminator[ValueRepr, DataRepr, Byte] = Discriminator(2)
  implicit val lazyDataRepr: Discriminator[ValueRepr, LazyDataRepr, Byte] = Discriminator(3)
  implicit val updatingDataRepr: Discriminator[ValueRepr, UpdatingDataRepr, Byte] = Discriminator(4)
  implicit val streamingDataRepr: Discriminator[ValueRepr, StreamingDataRepr, Byte] = Discriminator(5)

  implicit val streamingDataReprCodec: Codec[StreamingDataRepr] = cachedImplicit
  implicit val byteBufferCodec: Codec[ByteBuffer] = variableSizeBytes(int32, bytes).xmap(_.toByteBuffer, ByteVector.apply)

  implicit val codec: Codec[ValueRepr] = cachedImplicit
}

object TableOpCodec {
  implicit val tableOpDiscriminated: Discriminated[TableOp, Byte] = Discriminated(byte)
  implicit val groupAgg: Discriminator[TableOp, GroupAgg, Byte] = Discriminator(0)
  implicit val quantileBin: Discriminator[TableOp, QuantileBin, Byte] = Discriminator(1)
  implicit val select: Discriminator[TableOp, Select, Byte] = Discriminator(2)

  implicit val tableOpCodec: Codec[TableOp] = cachedImplicit
}