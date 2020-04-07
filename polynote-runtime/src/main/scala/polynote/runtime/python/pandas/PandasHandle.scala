package polynote.runtime.python.pandas

import java.io.DataOutput
import java.nio.ByteBuffer

import jep.python.{PyCallable, PyObject}
import polynote.runtime._
import polynote.runtime.python.{PythonObject, TypedPythonObject}
import shapeless.Witness

import scala.collection.immutable.ListMap
import scala.reflect.ClassTag

class PandasHandle(val handle: Int, df: PythonObject) extends StreamingDataRepr.Handle {

  private def typeFor(pandasType: String): Option[DataType] = pandasType match {
    case "int64" => Some(LongType)
    case "int32" => Some(IntType)
    case "int16" => Some(ShortType)
    case "int8"  => Some(ByteType)
    // TODO: we don't have unsigned integer types, but pandas does.
    case "boolean" => Some(BoolType)
    case "float64" => Some(DoubleType)
    case "float32" => Some(FloatType)
    case "string" | "category" => Some(StringType)
    case _ => None
  }

  override lazy val dataType: StructType = {
    val fields1 = df.runner.list(df.dtypes.items()).asScalaList.flatMap {
      field => field.asTuple2 match {
        case (field, dtype) =>
          typeFor(dtype.name.as[String]).flatMap {
            pandasType =>
              df.runner.typeName(field) match {
                case "tuple" =>
                  val path = field.asScalaList.map(_.as[String]).filterNot(_.isEmpty)
                  path match {
                    case Nil => None
                    case one :: Nil => Some((one, None, pandasType))
                    case one :: more => Some((one, Some(more.head), pandasType))
                  }
                case "str" =>
                  Some((field.as[String], None, pandasType))
                case _ =>
                  Some((df.runner.str(field).as[String], None, pandasType))
              }
          }.toList
      }
    }.foldLeft(ListMap.empty[String, Either[DataType, List[StructField]]]) {
      case (accum, (field, Some(subfield), dtype)) =>
        accum.getOrElse(field, Right(Nil)) match {
          case Right(existingFields) => accum + (field -> Right((existingFields :+ StructField(subfield, dtype))))
          case Left(_) => accum - field // the field is misbehaved - we think it's both a struct and not-a-struct. Discard it.
        }
      case (accum, (field, None, dtype)) =>
        accum + (field -> Left(dtype))
    }.toList.map {
      case (field, Right(List(StructField("", dtype)))) => StructField(field, dtype) // in a multi-index, even scalars get turned into multilevel thingies
      case (field, Right(structFields)) => StructField(field, StructType(structFields))
      case (field, Left(dtype)) => StructField(field, dtype)
    }

    StructType(fields1)
  }

  private def fieldEncoder(field: StructField): DataEncoder[PythonObject] = field match {
    case StructField(name, dataType) =>
      def fe[A >: Null : ClassTag, R](enc: DataEncoder[R], from: A => R): DataEncoder[PythonObject] =
        enc.contramap[PythonObject] {
          obj =>
            val pyValue = obj.__getitem__(name)
            // sigh... turns out simple things like float64 are actually numpy boxed things. Need to call item() to unbox them so jep can convert
            val a = if (df.runner.hasAttribute(pyValue, "item"))
              pyValue.item().as[A]
            else
              pyValue.as[A]
            from(a)
        }

      dataType match {
        case LongType => fe[java.lang.Number, Long](DataEncoder.long, _.longValue())
        case IntType  => fe[java.lang.Number, Int](DataEncoder.int, _.intValue())
        case ShortType => fe[java.lang.Number, Short](DataEncoder.short, _.shortValue())
        case ByteType  => fe[java.lang.Number, Byte](DataEncoder.byte, _.byteValue())
        case BoolType => fe[java.lang.Boolean, Boolean](DataEncoder.boolean, _.booleanValue())
        case DoubleType => fe[java.lang.Double, Double](DataEncoder.double, _.doubleValue())
        case StringType => fe[String, String](DataEncoder.string, identity)
        case st@StructType(fields) =>
          val fieldEncoders = fields.map(fieldEncoder).toArray
          new DataEncoder[PythonObject] {
            override def encode(dataOutput: DataOutput, value: PythonObject): Unit = {
              val struct = value.__getitem__(name)
              fieldEncoders.foreach(_.encode(dataOutput, struct))
            }
            override def dataType: DataType = st
            override def sizeOf(t: PythonObject): Int = {
              val struct = t.__getitem__(name)
              fieldEncoders.map(_.sizeOf(struct)).sum
            }
            override def numeric: Option[Numeric[PythonObject]] = None
          }
        case _ => throw new IllegalStateException(s"Can't handle $dataType for pandas")
      }
  }

  private lazy val encoder: DataEncoder[PythonObject] = {
    val fieldEncoders: Array[DataEncoder[PythonObject]] = dataType.fields.map(fieldEncoder).toArray

    new DataEncoder[PythonObject] {
      override def encode(dataOutput: DataOutput, row: PythonObject): Unit = fieldEncoders.foreach(_.encode(dataOutput, row))
      override lazy val dataType: DataType = PandasHandle.this.dataType
      override def sizeOf(row: PythonObject): Int = fieldEncoders.map(_.sizeOf(row)).sum
      override def numeric: Option[Numeric[PythonObject]] = None
    }
  }

  private lazy val size: Long = df.runner.len64(df)

  override lazy val knownSize: Option[Int] = Some(size).filter(_ <= Int.MaxValue.toLong).map(_.toInt)

  override def iterator: Iterator[ByteBuffer] = new PyGeneratorIterator(df.iterrows(), size).map {
    row => DataEncoder.writeSized(row.__getitem__(1))(encoder)  // iterrows gives tuples of row_index, row
  }

  @inline private def tryEither[T](thunk: => T): Either[Throwable, T] = try Right(thunk) catch {
    case err: Throwable => Left(err)
  }

  private def mkAggs(col: String, aggs: List[String]) = {
    def namedLambda(code: String, name: String) = df.runner.runJep {
      j =>
        val obj = j.getValue(code, classOf[PyObject])
        obj.setAttr("__name__", name)
        obj
    }

    col -> aggs.flatMap {
      case "quartiles" => List(
        namedLambda("lambda x: x.min()", s"quartiles($col).min"),
        namedLambda("lambda x: x.quantile(0.25)", s"quartiles($col).q1"),
        namedLambda("lambda x: x.quantile(0.5)", s"quartiles($col).median"),
        namedLambda("lambda x: x.quantile(0.75)", s"quartiles($col).q3"),
        namedLambda("lambda x: x.max()", s"quartiles($col).max"),
        namedLambda("lambda x: x.mean()", s"quartiles($col).mean")
      )
      case agg => List(namedLambda(s"lambda x: x.$agg()", s"$agg($col)"))
    }
  }

  override def modify(ops: List[TableOp]): Either[Throwable, Int => StreamingDataRepr.Handle] = ops.foldLeft(tryEither(df)) {
    (dfOrErr, op) => dfOrErr.right.flatMap {
      df => op match {
        case GroupAgg(cols, aggs) =>
          tryEither {
            val pandasAggs = aggs.groupBy(_._1).mapValues(_.map(_._2)).map((mkAggs _).tupled).mapValues(aggs => df.runner.listOf(aggs: _*))
            val next = df.groupby(df.runner.listOf(cols: _*), as_index = false)
              .agg(df.runner.dictOf(pandasAggs.toSeq: _*))

            val aggCols = next.columns.asScalaList
            val (l1, l2) = aggCols.map {
              col =>
                col.asTuple2Of[String, String] match {
                  case t@(_, "") => t
                  case (_, agg) => agg.split('.').toList match {
                    case Nil => throw new IllegalStateException()
                    case List(one) => (one, "")
                    case one :: more => (one, more.head)
                  }
                }
            }.unzip

            val index = df.runner.runJep(j => j.getValue("__polynote_mkindex__", classOf[PyCallable]).callAs(classOf[PyObject], l1.toArray, l2.toArray))
            next.updateDynamic("columns")(index)
            next
          }
        case Select(cols) =>
          tryEither {
            df.__getitem__(df.runner.listOf(cols: _*))
          }
        case op => Left(new UnsupportedOperationException(s"$op not yet supported for pandas"))
      }
    }
  }.right.map {
    df => new PandasHandle(_, df)
  }
}

class PyGeneratorIterator(obj: PythonObject, count: Long) extends Iterator[PythonObject] {
  @volatile private var consumed = 0

  override def hasNext: Boolean = consumed < count

  override def next(): PythonObject = {
    consumed += 1
    obj.__next__()
  }
}