package polynote.runtime.spark.reprs

import org.apache.spark.sql.{Encoders, Row, SparkSession}
import org.scalatest.{FreeSpec, Matchers}
import polynote.runtime.{DoubleType, IntType, OptionalType, ReprsOf, StreamingDataRepr, StringType, StructField, StructType}

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

case class Example(label: String, i: Int, d: Double)

object Example {
  // I don't get why Spark decides that label String is optional...
  val DataType: StructType = StructType(
    List(
      StructField("label", OptionalType(StringType)),
      StructField("i", IntType),
      StructField("d", DoubleType)
    )
  )
  val InputData: List[Example] =
    List(Example("a", 10, 10.0), Example("b", 11, 11.0), Example("c", 12, 12.0), Example("a", 12, 12.0))
  val InputDataSize: Int = InputData.length
}

class SparkReprsOfSuite extends FreeSpec with Matchers {
  val spark: SparkSession = {
    import org.apache.spark.repl.Main
    Main.conf.setAppName("Polynote tests")
    Main.conf.setMaster("local[*]")
    Main.conf.set("spark.driver.host", "127.0.0.1")
    Main.createSparkSession()
  }

  "Streaming repr of array of rows" - {
    "A simple list of rows" - {
      "representation" in {
        val rows = spark.createDataset(Example.InputData)(Encoders.product[Example])
          .toDF()
          .repartition(10)
          .selectExpr("*")
          .collect()

        val representation = implicitly[ReprsOf[Array[Row]]]
        val result = representation(rows)

        def decode(buf: ByteBuffer) = {
          buf.rewind()
          val present = buf.get()
          val label = if (present == 1) {
            val labelLength = buf.getInt()
            val labelArr = new Array[Byte](labelLength)
            buf.get(labelArr)
            new String(labelArr, StandardCharsets.UTF_8)
          } else {
            ""
          }
          val i = buf.getInt()
          val d = buf.getDouble()
          Example(label, i, d)
        }

        result match {
          case Array(StreamingDataRepr(handle, Example.DataType, Some(Example.InputDataSize))) =>
            StreamingDataRepr.getHandle(handle).fold(fail("Expected to find an handle, found None")) { h =>
              h.iterator.map(decode).toList should contain theSameElementsAs Example.InputData
            }
          case o => fail(s"Expected a StreamingDataRepr, not ${o.toList}")
        }
      }
    }
  }
}
