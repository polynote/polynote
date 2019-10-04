package polynote.runtime
package test

import org.scalatest.{FreeSpec, Matchers}

class StructDataEncoderSpec extends FreeSpec with Matchers {

  case class TestAllEncodable(first: Int, second: Double, third: Boolean, fourth: String)

  case class Nested(a: TestAllEncodable)

  case class NestedMap(a: Map[String, Double], b: TestAllEncodable, c: Map[String, TestAllEncodable])

  val e1 = implicitly[DataEncoder[NestedMap]]

  "can encode a case class" in {
    val e1 = DataEncoder.StructDataEncoder
    val encoder = implicitly[DataEncoder[TestAllEncodable]].asInstanceOf[DataEncoder.StructDataEncoder[TestAllEncodable]]

    encoder.dataType shouldEqual StructType(List(
      StructField("first", IntType),
      StructField("second", DoubleType),
      StructField("third", BoolType),
      StructField("fourth", StringType)
    ))

    val inst = TestAllEncodable(22, 2.2, true, "hello")

    val Some((firstGetter, firstEncoder)) = encoder.field("first")
    firstGetter(inst) shouldEqual 22
    firstEncoder.dataType shouldEqual IntType

  }

}
