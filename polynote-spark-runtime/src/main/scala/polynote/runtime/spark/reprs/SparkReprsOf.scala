package polynote.runtime.spark.reprs

import java.io.{ByteArrayOutputStream, DataOutput, DataOutputStream}
import java.nio.ByteBuffer

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, Dataset, types => sparkTypes}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.SpecializedGetters
import org.apache.spark.storage.StorageLevel
import polynote.runtime._

trait SparkReprsOf[A] extends ReprsOf[A]

private[reprs] sealed trait LowPrioritySparkReprsOf { self: SparkReprsOf.type =>

  implicit def dataset[T]: SparkReprsOf[Dataset[T]]= instance {
    ds => dataFrame(ds.toDF())
  }
  
}

object SparkReprsOf extends LowPrioritySparkReprsOf {
  
  private def dataTypeAndEncoder(dataType: sparkTypes.DataType, nullable: Boolean): Option[(DataType, DataOutput => SpecializedGetters => Int => Unit)] =
    if (nullable) {
      dataTypeAndEncoder(dataType, nullable = false).map {
        case (dt, encode) => OptionalType(dt) -> {
          out => {
            val encodeUnderlying = encode(out)
            row => index => if (row.isNullAt(index)) out.writeBoolean(false) else {
              out.writeBoolean(true)
              encodeUnderlying(row)(index)
            }
          }
        }
      }
    } else Option(dataType).collect {
      case sparkTypes.ByteType    => ByteType -> (out => row => index => DataEncoder.byte.encode(out, row.getByte(index)))
      case sparkTypes.BooleanType => BoolType -> (out => row => index => DataEncoder.boolean.encode(out, row.getBoolean(index)))
      case sparkTypes.ShortType   => ShortType -> (out => row => index => DataEncoder.short.encode(out, row.getShort(index)))
      case sparkTypes.IntegerType => IntType -> (out => row => index => DataEncoder.int.encode(out, row.getInt(index)))
      case sparkTypes.LongType    => LongType ->(out => row => index => DataEncoder.long.encode(out, row.getLong(index)))
      case sparkTypes.FloatType   => FloatType -> (out => row => index => DataEncoder.float.encode(out, row.getFloat(index)))
      case sparkTypes.DoubleType  => DoubleType -> (out => row => index => DataEncoder.double.encode(out, row.getDouble(index)))
      case sparkTypes.BinaryType  => BinaryType -> (out => row => index => DataEncoder.byteArray.encode(out, row.getBinary(index)))
      case sparkTypes.StringType  => StringType -> (out => row => index => DataEncoder.string.encode(out, row.getUTF8String(index).toString))
      case sparkTypes.ArrayType(sparkElementType, nullable) if dataTypeAndEncoder(sparkElementType, nullable).nonEmpty =>
        val (elementType, encode) = dataTypeAndEncoder(sparkElementType, nullable).get
        ArrayType(elementType) -> {
          out => {
            row => {
              index =>
                val arrayData = row.getArray(index)
                val len = arrayData.numElements()
                out.writeInt(len)
                val encodeItem = encode(out)(arrayData)
                var i = 0
                while (i < len) {
                  encodeItem(i)
                  i += 1
                }
            }

          }
        }
      case struct @ sparkTypes.StructType(_) =>
        val (structType, encode) = structDataTypeAndEncoder(struct)
        structType -> (out => row => index => encode(out, row.getStruct(index, struct.fields.length)))
      case sparkTypes.MapType(sparkKeyType, sparkValueType, nullValues)
          if dataTypeAndEncoder(sparkKeyType, nullable = false).nonEmpty && dataTypeAndEncoder(sparkValueType, nullValues).nonEmpty =>
        val (keyType, encodeKey) = dataTypeAndEncoder(sparkKeyType, nullable = false).get
        val (valueType, encodeValue) = dataTypeAndEncoder(sparkValueType, nullValues).get
        MapType(keyType, valueType) -> {
          out =>
            row => {
              index =>
                val mapData = row.getMap(index)
                val len = mapData.numElements()
                out.writeInt(len)
                val keyData = mapData.keyArray()
                val valueData = mapData.valueArray()
                val encodeItem: Int => Unit = {
                  i =>
                    encodeKey(out)(keyData)(i)
                    encodeValue(out)(valueData)(i)
                }

                var i = 0
                while (i < len) {
                  encodeItem(i)
                  i += 1
                }
            }
        }

    }

  private def structDataTypeAndEncoder(schema: sparkTypes.StructType): (StructType, (DataOutput, InternalRow) => Unit) = {
    val (fieldTypes, fieldEncoders) = schema.fields.zipWithIndex.flatMap {
      case (sparkTypes.StructField(name, dataType, nullable, _), index) =>
        Option(dataType).flatMap(dataTypeAndEncoder(_, nullable)).map {
          case (dt, enc) => StructField(name, dt) -> ((out: DataOutput, row: InternalRow) => enc(out)(row)(index))
        }
    }.unzip

    StructType(fieldTypes.toList) -> {
      (out, row) =>
        fieldEncoders.foreach(_.apply(out, row))
    }
  }

  private[polynote] class FixedSizeDataFrameDecoder(structType: StructType, encode: (DataOutput, InternalRow) => Unit) extends (InternalRow => Array[Byte]) with Serializable {
    assert(structType.size >= 0)
    override def apply(row: InternalRow): Array[Byte] = {
        // TODO: share/reuse this buffer? Any reason to be threadsafe?
      val arr = new Array[Byte](structType.size)
      val buf = ByteBuffer.wrap(arr)
      encode(new DataEncoder.BufferOutput(buf), row)
      arr
    }
  }

  private[polynote] class VariableSizeDataFrameDecoder(structType: StructType, encode: (DataOutput, InternalRow) => Unit) extends (InternalRow => Array[Byte]) with Serializable {
    override def apply(row: InternalRow): Array[Byte] = {
      val out = new ByteArrayOutputStream()
      try {
        encode(new DataOutputStream(out), row)
        out.toByteArray
      } finally {
        out.close()
      }
    }
  }


  private[polynote] class DataFrameHandle(
    val handle: Int,
    dataFrame: DataFrame
  ) extends StreamingDataRepr.Handle with Serializable {

    private val originalStorage = dataFrame.storageLevel
    private val (structType, encode) = structDataTypeAndEncoder(dataFrame.schema)


    val dataType: DataType = structType
    val knownSize: Option[Int] = None

    // TODO: It might be nice to iterate instead of collect, but maybe in a better way than toLocalIterator...
    private lazy val collectedData = {
      val rowToBytes: InternalRow => Array[Byte] = if (structType.size >= 0) {
        new FixedSizeDataFrameDecoder(structType, encode)
      } else {
        new VariableSizeDataFrameDecoder(structType, encode)
      }

      dataFrame.limit(1000000).queryExecution.toRdd.map(rowToBytes).collect()
    }

    def iterator: Iterator[ByteBuffer] = collectedData.iterator.map(ByteBuffer.wrap)


    def modify(ops: List[TableOp]): Either[Throwable, Int => StreamingDataRepr.Handle] = {
      import org.apache.spark.sql.functions, functions.{when, col, sum, lit, struct, count, approx_count_distinct, avg}
      import org.apache.spark.sql.Column
      import org.apache.spark.sql.catalyst.analysis.UnresolvedAttribute
      import org.apache.spark.sql.catalyst.expressions.aggregate.ApproximatePercentile
      import org.apache.spark.sql.catalyst.expressions.Literal


      @inline def tryEither[T](thunk: => T): Either[Throwable, T] = try Right(thunk) catch {
        case err: Throwable => Left(err)
      }

      def toAggregate(strings: (String, String)): (List[Column], Option[DataFrame => DataFrame]) = strings match {
        case (name, "quartiles") =>
          val quartilesAgg = new Column(new ApproximatePercentile(UnresolvedAttribute.quotedString(name), Literal(Array(0.0, 0.25, 0.5, 0.75, 1.0))).toAggregateExpression())
            .as(s"${name}_quartiles_array")
          val quartilesCol = col(s"${name}_quartiles_array")
          val meanAgg = functions.avg(name) as s"${name}_mean"
          val meanCol = col(s"${name}_mean")
          val post = (df: DataFrame) => df.withColumn(
            s"quartiles($name)",
            struct(quartilesCol(0) as "min", quartilesCol(1) as "q1", quartilesCol(2) as "median", meanCol as "mean", quartilesCol(3) as "q3", quartilesCol(4) as "max")
          ).drop(quartilesCol).drop(meanCol)
          List(quartilesAgg, meanAgg) -> Some(post)

        case (name, "sum") => List(sum(col(name)) as s"sum($name)") -> None
        case (name, "count") => List(count(col(name).cast("double")) as s"count($name)") -> None
        case (name, "approx_count_distinct") => List(approx_count_distinct(col(name)) as s"approx_count_distinct($name)") -> None
        case (name, "mean") => List(avg(col(name)) as s"mean($name)") -> None



        case (_, op) => throw new UnsupportedOperationException(op)
      }

      ops.foldLeft(tryEither(dataFrame)) {
        (dfOrErr, op) => dfOrErr.right.flatMap {
          df => op match {
            case GroupAgg(cols, aggs) => tryEither {
              val (aggCols, postFns) = aggs.map(toAggregate).unzip match {
                case (aggColss, postFns) => aggColss.flatten -> postFns.flatten
              }
              val post = postFns.foldLeft(identity[DataFrame] _)(_ andThen _)
              post(df.groupBy(cols.head, cols.tail: _*).agg(aggCols.head, aggCols.tail: _*))
            }

            case QuantileBin(column, binCount, err) => tryEither {
              val probabilities = (BigDecimal(0.0) to BigDecimal(1.0) by (BigDecimal(1.0) / binCount)).map(_.doubleValue).toArray
              val quantiles = df.stat.approxQuantile(column, probabilities, err)
              val c = col(column)
              val cases = (1 until quantiles.length - 1).foldLeft(when(c < quantiles(1), quantiles.head)) {
                (accum, index) => accum.when(c >= quantiles(index) && c < quantiles(index + 1), quantiles(index))
              }.otherwise(quantiles.last)
              df.withColumn(s"${column}_quantized", cases)
            }

            case Select(columns) => tryEither(df.select(columns.head, columns.tail: _*))
          }
        }
      }.right.map {
        modifiedDataFrame => new DataFrameHandle(_, modifiedDataFrame)
      }
    }

    override def release(): Unit = {
      if (originalStorage == StorageLevel.NONE) {
        dataFrame.unpersist(false)
      } else {
        try {
          dataFrame.persist(originalStorage)
        } catch {
          case err: Throwable => dataFrame.unpersist(false)
        }
      }
      super.release()
    }
  }

  import org.apache.spark.sql.{Dataset, DataFrame, SparkSession}

  def instance[T](reprs: T => Array[ValueRepr]): SparkReprsOf[T] = new SparkReprsOf[T] {
    def apply(value: T): Array[ValueRepr] = reprs(value)
  }

  implicit val dataFrame: SparkReprsOf[DataFrame] = {
    instance {
      df => Array(StreamingDataRepr.fromHandle(new DataFrameHandle(_, df)))
    }
  }

  implicit val sparkSession: SparkReprsOf[SparkSession] = {
    instance {
      sess =>
        val uiLink = sess.sparkContext.uiWebUrl.map { url =>
          s"""<span class="field-name">Spark UI</span><a href="$url" class="link" target="_blank">$url</a>"""
        }.getOrElse("""<span class="field-name error">Spark UI url not found!</span>""")

        // TODO: this is pretty fragile: tight coupling to the display_content implementation.
        //  We should have a better way to inject data for display instead of doing this.
        val config = Seq(
          """
            |<details class="object-display">
            |  <summary class="object-summary"><span class="summary-content"><span>SparkConf</span></span></summary>
            |  <ul class="object-fields">
          """.stripMargin) ++ sess.conf.getAll.map {
          case (k, v) =>
            s"""<li>
              |<span class="field-name">$k</span><span class="string">$v</span>
              |</li>
            """.stripMargin
        } ++ Seq(
          """
            |</ul></details>
          """.stripMargin)

        val html =
          s"""
             |<div class="object-display spark-ui">
             |  $uiLink
             |</div>
             |${config.mkString("\n")}
           """.stripMargin
        Array(MIMERepr("text/html", html))
    }
  }

}

