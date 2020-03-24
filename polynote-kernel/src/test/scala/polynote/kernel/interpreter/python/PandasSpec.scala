package polynote.kernel.interpreter.python

import org.scalatest.{FreeSpec, Matchers}
import polynote.kernel.{ResultValue, ScalaCompiler}
import polynote.kernel.interpreter.State
import polynote.runtime.python.TypedPythonObject
import polynote.runtime.{DoubleType, GroupAgg, IntType, LongType, StreamingDataRepr, StructField, StructType}
import polynote.testing.InterpreterSpec
import polynote.testing.kernel.MockEnv
import shapeless.Witness

class PandasSpec extends FreeSpec with Matchers with InterpreterSpec {
  val interpreter: PythonInterpreter = PythonInterpreter(None).provide(ScalaCompiler.Provider.of(compiler)).runIO()
  interpreter.init(State.Root).provideSomeLayer(MockEnv.init).runIO()

  "Pandas interop" - {

    "StreamingDataRepr of a pandas DataFrame" - {

      "encodes data correctly" in {

        val result = interp1(
          """import pandas as pd
            |pd.DataFrame([[1.1, 1], [2.2, 2], [3.3, 3]], columns=['floatcol', 'intcol'])
            |""".stripMargin)

        result.state.scope.head match {
          case ResultValue(name, typeName, _, _, value: TypedPythonObject[Witness.`"DataFrame"`.T @unchecked], _, _) =>
            name shouldEqual "Out"
            typeName shouldEqual "TypedPythonObject[DataFrame]"
            val reprs = TypedPythonObject.dataFrameReprs(value)

            val Some(handle) = reprs.collectFirst {
              case s: StreamingDataRepr =>
                s.dataType shouldEqual StructType(List(
                  StructField("floatcol", DoubleType),
                  StructField("intcol", LongType)
                ))

                StreamingDataRepr.getHandle(s.handle)
            }.flatten

            val List(row1, row2, row3) = handle.iterator.toList.map {
              buf =>
                val d = buf.getDouble()
                val i = buf.getLong()
                buf.rewind()
                (d, i)
            }

            row1 shouldEqual (1.1, 1)
            row2 shouldEqual (2.2, 2)
            row3 shouldEqual (3.3, 3)
        }

      }

      "aggregates correctly" in {
        val result = interp1(
          """import pandas as pd
            |pd.DataFrame([[1.0, 1], [2.0, 1], [3.0, 1], [2.0, 1], [2.0, 2], [4.0, 2]], columns=['floatcol', 'intcol'])
            |""".stripMargin)

        result.state.scope.head match {
          case ResultValue(name, typeName, _, _, value: TypedPythonObject[Witness.`"DataFrame"`.T@unchecked], _, _) =>
            name shouldEqual "Out"
            typeName shouldEqual "TypedPythonObject[DataFrame]"
            val reprs = TypedPythonObject.dataFrameReprs(value)

            val Some(handle) = reprs.collectFirst {
              case s: StreamingDataRepr =>
                s.dataType shouldEqual StructType(
                  List(
                    StructField("floatcol", DoubleType),
                    StructField("intcol", LongType)
                  ))

                StreamingDataRepr.getHandle(s.handle)
            }.flatten

            val handle2 = StreamingDataRepr.getHandle(
              StreamingDataRepr.fromHandle(
                handle.modify(
                  List(
                    GroupAgg(
                      List("intcol"),
                      List("floatcol" -> "quartiles", "floatcol" -> "mean", "floatcol" -> "count")
                    )
                  )).right.get
              ).handle
            ).get

            handle2.dataType shouldEqual StructType(
              List(
                StructField("intcol", LongType),
                StructField(
                  "quartiles(floatcol)", StructType(
                    List(
                      StructField("min", DoubleType), StructField("q1", DoubleType), StructField("median", DoubleType),
                      StructField("q3", DoubleType), StructField("max", DoubleType), StructField("mean", DoubleType))
                  )),
                StructField("mean(floatcol)", DoubleType),
                StructField("count(floatcol)", DoubleType) // not sure why pandas count is double
              ))

            val List(row1, row2) = handle2.iterator.map {
              buf =>
                val struct = (buf.getLong(), (buf.getDouble(), buf.getDouble(), buf.getDouble(), buf.getDouble(), buf.getDouble(), buf.getDouble()), buf.getDouble(), buf.getDouble())
                buf.rewind()
                struct
            }.toList

            row1 shouldEqual (
              1,
              (1.0, 1.75, 2.0, 2.25, 3.0, 2.0),
              2.0,
              4.0
            )

            row2 shouldEqual (
              2,
              (2.0, 2.5, 3.0, 3.5, 4.0, 3.0),
              3.0,
              2.0
            )
        }
      }

    }

  }

}
