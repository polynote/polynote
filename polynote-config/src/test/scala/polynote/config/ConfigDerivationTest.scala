package polynote.config

import org.scalatest.{FreeSpec, Matchers}

class ConfigDerivationTest extends FreeSpec with Matchers {
  import ConfigDerivationTest._
  import ConfigNode._

  "one-level derivation" - {
    val decoder = ConfigDecoder.derive[Test1]

    "decodes the fields" in {
      decoder.decode(ObjectNode(List(
        "string_field" -> StringNode("hello"),
        "int_field" -> StringNode("22"),
        "optional_boolean" -> StringNode("true")
      )), DecodingContext.Empty) shouldEqual Right(Test1("hello", 22, Some(true)))
    }

    "optional things don't fail if missing" in {
      decoder.decode(ObjectNode(List(
        "string_field" -> StringNode("hello"),
        "int_field" -> StringNode("22")
      )), DecodingContext.Empty) shouldEqual Right(Test1("hello", 22, None))
    }

    "non-optional things fail if missing" in {
      val result = decoder.decode(ObjectNode(Nil), DecodingContext.Empty)
      result.left.get should contain theSameElementsAs List(
        DecodingError("Missing required field string_field", DecodingContext.Empty),
        DecodingError("Missing required field int_field", DecodingContext.Empty)
      )
    }

  }

  "one-level derivation with default values" - {
    val decoder = ConfigDecoder.derive[Test2]

    "default values are used when fields aren't provided" in {
      decoder.decode(ObjectNode(List(
        "thing_one" -> StringNode("100:200")
      )), DecodingContext.Empty) shouldEqual Right(Test2(Range.inclusive(100, 200), "a default", Some("a Some default"), None))
    }

    "default values aren't used when fields are provided" in {
      decoder.decode(ObjectNode(List(
        "thing_one" -> StringNode("100:200"),
        "thing_two" -> StringNode("not the default"),
        "thing_three" -> StringNode("not the Some default"),
        "thing_four" -> StringNode("false")
      )), DecodingContext.Empty) shouldEqual Right(Test2(Range.inclusive(100, 200), "not the default", Some("not the Some default"), Some(false)))
    }

    "default values aren't used when there is a decoding error" in {
      decoder.decode(ObjectNode(List(
        "thing_one" -> StringNode("100:200"),
        "thing_two" -> StringNode("not the default"),
        "thing_three" -> StringNode("not the Some default"),
        "thing_four" -> StringNode("bad boolean")
      )), DecodingContext.Empty).left.get should contain theSameElementsAs List(
        DecodingError("Invalid boolean value bad boolean (must be true or false)", DecodingContext.Empty.inObject("thing_four"))
      )
    }
  }

  "one-level nested derivation" - {

  }

}

object ConfigDerivationTest {

  case class Test1(stringField: String, intField: Int, optionalBoolean: Option[Boolean])

  case class Test2(thingOne: Range, thingTwo: String = "a default", thingThree: Option[String] = Some("a Some default"), thingFour: Option[Boolean] = None)

  case class Nested1(testOne: Test1, testTwos: List[Test2])
}
