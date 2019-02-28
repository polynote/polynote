package polynote.runtime

import java.io.{ByteArrayOutputStream, DataOutput, DataOutputStream}
import java.nio.{ByteBuffer, ShortBuffer}

import scala.collection.immutable.Queue

trait ReprsOf[T] extends (T => Array[ValueRepr])

object ReprsOf extends SparkReprs {

  def instance[T](reprs: T => Array[ValueRepr]): ReprsOf[T] = new ReprsOf[T] {
    def apply(value: T): Array[ValueRepr] = reprs(value)
  }

  implicit val byte: ReprsOf[Byte] = instance {
    byte => Array(DataRepr(ByteType, ByteBuffer.wrap(Array(byte))))
  }

  implicit val boolean: ReprsOf[Boolean] = instance {
    bool =>
      val byte: Byte = if (bool) 1 else 0
      Array(DataRepr(BoolType, ByteBuffer.wrap(Array(byte))))
  }

  implicit val short: ReprsOf[Short] = instance {
    short => Array(DataRepr(ShortType, ByteBuffer.wrap(Array((0xFF & (short >> 8)).toByte, (0xFF & short).toByte))))
  }

  @inline private def intToBuf(int: Int) =
    ByteBuffer.wrap(Array((0xFF & (int >> 24)).toByte, (0xFF & (int >> 16)).toByte, (0xFF & (int >> 8)).toByte, (0xFF & int).toByte))

  implicit val int: ReprsOf[Int] = instance {
    int => Array(DataRepr(IntType, intToBuf(int)))
  }

  @inline private def longToBuf(long: Long) =
    ByteBuffer.wrap(
      Array(
        (0xFF & (long >> 56)).toByte, (0xFF & (long >> 48)).toByte, (0xFF & (long >> 40)).toByte, (0xFF & (long >> 32)).toByte,
        (0xFF & (long >> 24)).toByte, (0xFF & (long >> 16)).toByte, (0xFF & (long >> 8)).toByte, (0xFF & long).toByte))

  implicit val long: ReprsOf[Long] = instance {
    long => Array(DataRepr(LongType, longToBuf(long)))
  }

  implicit val float: ReprsOf[Float] = instance {
    float => Array(DataRepr(FloatType, intToBuf(java.lang.Float.floatToIntBits(float))))
  }

  implicit val double: ReprsOf[Double] = instance {
    double => Array(DataRepr(DoubleType, longToBuf(java.lang.Double.doubleToLongBits(double))))
  }
}

private[runtime] sealed trait SparkReprs { self: ReprsOf.type =>

  import org.apache.spark.sql.{Dataset, DataFrame}


  implicit def dataFrame: ReprsOf[DataFrame] = {
    import org.apache.spark.sql.{types => sparkTypes}
    import org.apache.spark.sql.catalyst.InternalRow

    def dataTypeAndEncoder(schema: sparkTypes.StructType): (StructType, (DataOutput, InternalRow) => Unit) = {
      val (fieldTypes, fieldEncoders) = schema.fields.zipWithIndex.flatMap {
        case (sparkTypes.StructField(name, dataType, nullable, _), index) =>
          Option(dataType).collect[(DataType, (DataOutput, InternalRow) => Unit)] {
            case sparkTypes.ByteType    => ByteType -> ((out, row) => DataEncoder.byte.encode(out, row.getByte(index)))
            case sparkTypes.BooleanType => BoolType -> ((out, row) => DataEncoder.boolean.encode(out, row.getBoolean(index)))
            case sparkTypes.ShortType   => ShortType -> ((out, row) => DataEncoder.short.encode(out, row.getShort(index)))
            case sparkTypes.IntegerType => IntType -> ((out, row) => DataEncoder.int.encode(out, row.getInt(index)))
            case sparkTypes.LongType    => LongType ->((out, row) => DataEncoder.long.encode(out, row.getLong(index)))
            case sparkTypes.FloatType   => FloatType -> ((out, row) => DataEncoder.float.encode(out, row.getFloat(index)))
            case sparkTypes.DoubleType  => DoubleType -> ((out, row) => DataEncoder.double.encode(out, row.getDouble(index)))
            case sparkTypes.BinaryType  => BinaryType -> ((out, row) => DataEncoder.byteArray.encode(out, row.getBinary(index)))
            case sparkTypes.StringType  => StringType -> ((out, row) => DataEncoder.string.encode(out, row.getString(index)))
            case struct @ sparkTypes.StructType(_) =>
              val (structType, encode) = dataTypeAndEncoder(struct)
              structType -> ((out, row) => encode(out, row.getStruct(index, struct.fields.length)))
          }.map {
            case (dt, enc) => StructField(name, dt) -> enc
          }
      }.unzip

      StructType(fieldTypes.toList) -> {
        (out, row) =>
          fieldEncoders.foreach(_.apply(out, row))
      }
    }

    instance {
      df =>
        val (structType, encode) = dataTypeAndEncoder(df.schema)
        val rowToBytes: InternalRow => ByteBuffer = if (structType.size >= 0) {
          row: InternalRow =>
            // TODO: share/reuse this buffer? Any reason to be threadsafe?
            val buf = ByteBuffer.allocate(structType.size)
            encode(new DataEncoder.BufferOutput(buf), row)
            buf
        } else {
          row: InternalRow =>
            val out = new ByteArrayOutputStream()
            try {
              encode(new DataOutputStream(out), row)
              ByteBuffer.wrap(out.toByteArray)
            } finally {
              out.close()
            }
        }

        Array(
          StreamingDataRepr(
            structType,
            df.queryExecution.toRdd.toLocalIterator.map(rowToBytes))
        )
    }
  }

}
