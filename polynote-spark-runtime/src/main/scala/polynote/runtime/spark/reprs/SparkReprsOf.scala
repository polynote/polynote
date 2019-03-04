package polynote.runtime.spark.reprs

import java.io.{ByteArrayOutputStream, DataOutput, DataOutputStream}
import java.nio.ByteBuffer

import polynote.runtime._

trait SparkReprsOf[A] extends ReprsOf[A]

object SparkReprsOf {

  import org.apache.spark.sql.{Dataset, DataFrame}

  def instance[T](reprs: T => Array[ValueRepr]): SparkReprsOf[T] = new SparkReprsOf[T] {
    def apply(value: T): Array[ValueRepr] = reprs(value)
  }

  implicit val dataFrame: SparkReprsOf[DataFrame] = {
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

