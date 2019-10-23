package polynote.runtime
package test

import org.scalacheck.{Arbitrary, Gen}
import Arbitrary.arbitrary
import org.scalatest.{FreeSpec, Matchers}
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

import scala.reflect.runtime.universe.TypeTag

/**
  * Exercises some DataReprs to make sure they don't crash. Doesn't do any validations, though, because we don't have
  * the decoding side in Scala land. TODO: an integration test with the JS code to make sure they get decoded correctly too
  */
class DataReprsTest extends FreeSpec with Matchers with ScalaCheckDrivenPropertyChecks {

  implicit val arbString: Arbitrary[String] = Arbitrary(Gen.listOf(arbitrary[Char]).map(_.mkString))

  def test[T : Arbitrary](implicit typeTag: TypeTag[T], reprsOf: ReprsOf.DataReprsOf[T]): Unit = {
    s"${typeTag}" in {
      forAll {
        (value: T) => reprsOf.encode(value)
      }
    }
  }

  "DataReprsOf checks" - {
    "Maps" - {
      test[Map[String, String]]
      test[Map[String, Int]]
      test[Map[String, Map[String, Int]]]
      test[List[Map[Int, String]]]
    }

    "Sequences" - {
      test[List[String]]
      test[List[Int]]
    }

    "Structs" - {
      test[(String, Int, Map[Int, String])]
      test[List[(Int, Boolean, String, String, Option[Int], Option[String], List[String], Map[String, Int])]]
    }
  }

}
