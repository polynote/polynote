package polynote.runtime

import java.io.{ByteArrayOutputStream, DataOutput, DataOutputStream}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import polynote.runtime
import shapeless.HNil

import scala.collection.GenSeq
import scala.concurrent.Future

trait ReprsOf[T] extends Serializable {
  def apply(value: T): Array[ValueRepr]
}

object ReprsOf extends ExpandedScopeReprs {

  // If a data value is larger than 1 MiB, we'll make it lazy so it doesn't get spammed to the client
  // In the future we could make this configurable by the client.
  private val EagerSizeThreshold = 1024 * 1024

  def instance[T](reprs: T => Array[ValueRepr]): ReprsOf[T] = new ReprsOf[T] {
    def apply(value: T): Array[ValueRepr] = reprs(value)
  }

  abstract class DataReprsOf[T](val dataType: DataType) extends ReprsOf[T] {
    val encode: T => ByteBuffer
  }

  class StrictDataReprsOf[T](dataType: DataType, val encode: T => ByteBuffer) extends DataReprsOf[T](dataType) {
    def apply(t: T): Array[ValueRepr] = try {
      Array(DataRepr(dataType, encode(t)))
    } catch {
      case err: Throwable => Array()
    }
  }

  object DataReprsOf {
    def apply[T](dataType: DataType)(encode: T => ByteBuffer): DataReprsOf[T] = new StrictDataReprsOf(dataType, encode)

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
        buf.rewind()
        buf
    }

    implicit val byteArray: DataReprsOf[Array[Byte]] = DataReprsOf(BinaryType)(ByteBuffer.wrap)

    implicit def fromDataEncoder[T](implicit dataEncoder: DataEncoder[T]): DataReprsOf[T] = new DataReprsOf[T](dataEncoder.dataType) {
      val encode: T => ByteBuffer = t => DataEncoder.writeSized(t)
      override def apply(value: T): Array[ValueRepr] = dataEncoder.sizeOf(value) match {
        case s if s >= 0 && s <= EagerSizeThreshold => Array(DataRepr(dataType, DataEncoder.writeSized(value, s)))
        case s if s >= 0 => Array(LazyDataRepr(dataType, DataEncoder.writeSized(value, s), Some(s))) // writeSized is suspended byname
        case _ => Array(LazyDataRepr(dataType, DataEncoder.writeSized(value), None)) // writeSized is suspended byname
      }
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

  val empty: ReprsOf[Any] = instance(_ => Array.empty)

  implicit val sparkSession: ReprsOf[Runtime.type] = {
    instance {
      r =>
        val html =
          s"""<div class="object-display server-info">
             | <span class="field-name">Server Version</span><span class="string">${r.version}</span></br>
             | <span class="field-name">Server Commit</span><span class="string">${r.commit}</span></br>
             |</div>
           """.stripMargin
        Array(MIMERepr("text/html", html))

    }
  }

}


private[runtime] trait ExpandedScopeReprs extends CollectionReprs { self: ReprsOf.type =>

  implicit def expanded[T]: ReprsOf[T] = macro macros.ExpandedScopeMacros.resolveFromScope

}

private[runtime] trait CollectionReprs extends FromDataReprs { self: ReprsOf.type =>

  implicit def structSeq[F[X] <: Seq[X], A](implicit structEncoder: DataEncoder.StructDataEncoder[A]): ReprsOf[F[A]] =
    instance(seq => Array(StreamingDataRepr.fromHandle(new StructSeqStreamHandle[A, A](_, seq, identity, structEncoder))))

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


  private[runtime] case class StructSeqStreamHandle[A, B](handle: Int, data: Seq[A], transform: Seq[A] => Seq[B], enc: DataEncoder.StructDataEncoder[B]) extends StreamingDataRepr.Handle {
    def dataType: DataType = enc.dataType
    lazy val knownSize: Option[Int] = if (data.hasDefiniteSize) Some(data.size) else None
    def iterator: Iterator[ByteBuffer] = transform(data).iterator.map(b => DataEncoder.writeSized[B](b)(enc))

    private trait Aggregator[T] {
      def accumulate(value: B): Unit
      def summarize(): T
      def encoder: DataEncoder[T]
      def resultName: String
    }

    private class QuartileAggregator(name: String, getter: B => Double) extends Aggregator[Quartiles] {
      private val values = new Array[Double](data.size)
      private var index = 0
      private var mean = 0.0

      override def accumulate(value: B): Unit = {
        val x = getter(value)
        values(index) = x
        index += 1
        val delta = x - mean
        mean += delta / index
      }

      override def summarize(): Quartiles = {
        java.util.Arrays.sort(values, 0, index)

        val quarter = index >> 2

        Quartiles(
          values(0),
          values(quarter),
          values(index >> 1),
          mean,
          values(index - quarter),
          values(index - 1)
        )
      }

      val encoder: DataEncoder[Quartiles] = Quartiles.dataEncoder
      val resultName: String = s"quartiles($name)"
    }

    private class SumAggregator(name: String, getter: B => Double) extends Aggregator[Double] {
      private var sum = 0.0
      override def accumulate(value: B): Unit = sum += getter(value)
      override def summarize(): Double = sum
      val encoder: DataEncoder[Double] = DataEncoder.double
      val resultName: String = s"sum($name)"
    }

    private class CountAggregator(name: String) extends Aggregator[Long] {
      private var count = 0L
      override def accumulate(value: B): Unit = count += 1
      override def summarize(): Long = count
      val encoder: DataEncoder[Long] = DataEncoder.long
      val resultName: String = s"count($name)"
    }

    private class MeanAggregator(name: String, getter: B => Double) extends Aggregator[Double] {
      private var count = 0
      private var mean = 0.0
      private var sumSquaredDiffs = 0.0

      override def accumulate(value: B): Unit = {
        val x = getter(value)
        count += 1
        val delta = x - mean
        mean += delta / count
      }

      override def summarize(): Double = mean
      val encoder: DataEncoder[Double] = DataEncoder.double
      val resultName: String = s"mean($name)"
    }

    private def aggregate(col: String, aggName: String): Aggregator[_] = {
      def numericEncoder = enc.field(col) match {
        case Some((getter, colEnc)) if colEnc.numeric.nonEmpty =>
          val numeric = colEnc.numeric.get.asInstanceOf[Any => Double]
          getter andThen numeric
        case Some(_) => throw new IllegalArgumentException(s"Field $col is not numeric; cannot compute $aggName")
        case None => throw new IllegalArgumentException(s"No field $col in struct")
      }

      aggName match {
        case "quartiles" => new QuartileAggregator(col, numericEncoder)
        case "sum"       => new SumAggregator(col, numericEncoder)
        case "count"     => new CountAggregator(col)
        case "mean"      => new MeanAggregator(col, numericEncoder)
        case _ => throw new IllegalArgumentException(s"No aggregation $aggName available")
      }
    }

    def modify(ops: List[TableOp]): Either[Throwable, Int => StreamingDataRepr.Handle] = {
      ops match {
        case Nil => Right(StructSeqStreamHandle[A, B](_, data, transform, enc))
        case GroupAgg(cols, aggs) :: rest if cols.nonEmpty =>
          try {
            val groupingFields = cols.map {
              col => enc.field(col).getOrElse(throw new IllegalArgumentException(s"No field $col in struct"))
            }

            val getters = groupingFields.map(_._1)

            val aggregators = aggs.map {
              case (col, aggName) => aggregate(col, aggName)
            }

            val groupTransform = (bs: Seq[B]) => bs.groupBy(b => getters.map(_.apply(b))).toSeq.map {
              case (groupCols, group) =>
                group.foreach {
                  b => aggregators.foreach {
                    agg => agg.accumulate(b)
                  }
                }
                val aggregates = aggregators.map(_.summarize())
                (groupCols ::: aggregates).toArray
            }

            val groupedType = StructType(
              (cols.zip(groupingFields.map(_._2.dataType)) ++ aggregators.map(agg => agg.resultName -> agg.encoder.dataType))
                .map((StructField.apply _).tupled))

            val groupedEncoders = groupingFields.map(_._2.asInstanceOf[DataEncoder[Any]]) ++ aggregators.map(_.encoder.asInstanceOf[DataEncoder[Any]])

            val groupedEncoder = new runtime.DataEncoder.StructDataEncoder[Array[Any]](groupedType) {
              def field(name: String): Option[(Array[Any] => Any, DataEncoder[_])] = {
                groupedType.fields.indexWhere(_.name == name) match {
                  case -1 => None
                  case index => Some((arr => arr(index), groupedEncoders(index)))
                }
              }

              def encode(dataOutput: DataOutput, value: Array[Any]): Unit = {
                val encs = groupedEncoders.iterator
                var i = 0
                while (i < value.length) {
                  encs.next().encode(dataOutput, value(i))
                  i += 1
                }
              }

              def sizeOf(t: Array[Any]): Int = {
                val encs = groupedEncoders.iterator
                var size = encs.next().sizeOf(t(0))
                var i = 1
                while (i < t.length) {
                  size = DataEncoder.combineSize(size, encs.next().sizeOf(t(i)))
                  i += 1
                }
                size
              }
            }

            Right((newHandle: Int) => new StructSeqStreamHandle[A, Array[Any]](newHandle, data, transform andThen groupTransform, groupedEncoder))
          } catch {
            case err: Throwable => Left(err)
          }
      }
    }
  }


}

private[runtime] trait FromDataReprs { self: ReprsOf.type =>
  implicit def fromDataReprs[T](implicit dataReprsOfT: DataReprsOf[T]): ReprsOf[T] = dataReprsOfT
}
