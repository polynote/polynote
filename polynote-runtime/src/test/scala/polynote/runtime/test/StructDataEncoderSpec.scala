package polynote.runtime
package test

import org.scalatest.{FreeSpec, Matchers}

class StructDataEncoderSpec extends FreeSpec with Matchers {

  case class TestAllEncodable(first: Int, second: Double, third: Boolean, fourth: String)

  case class Nested(a: TestAllEncodable)

  case class NestedMap(a: Map[String, Double], b: TestAllEncodable, c: Map[String, TestAllEncodable])

  private val inst = TestAllEncodable(22, 2.2, true, "hello")

  "can encode a case class" in {
    val e1 = DataEncoder.StructDataEncoder.caseClassMacro[TestAllEncodable]
    val encoder = implicitly[DataEncoder[TestAllEncodable]].asInstanceOf[DataEncoder.StructDataEncoder[TestAllEncodable]]

    encoder.dataType shouldEqual StructType(List(
      StructField("first", IntType),
      StructField("second", DoubleType),
      StructField("third", BoolType),
      StructField("fourth", StringType)
    ))


    val Some((firstGetter, firstEncoder)) = encoder.field("first")
    firstGetter(inst) shouldEqual 22
    firstEncoder.dataType shouldEqual IntType

    encoder.encodeDisplayString(inst) shouldEqual
      s"""TestAllEncodable(
        |  first = ${inst.first},
        |  second = ${inst.second},
        |  third = ${inst.third},
        |  fourth = ${inst.fourth}
        |)""".stripMargin

  }

  "formats nested things properly" in {
    implicitly[DataEncoder[Nested]].encodeDisplayString(Nested(inst)) shouldEqual
      s"""Nested(
        |  a = TestAllEncodable(
        |    first = ${inst.first},
        |    second = ${inst.second},
        |    third = ${inst.third},
        |    fourth = ${inst.fourth}
        |  )
        |)""".stripMargin
  }

}
