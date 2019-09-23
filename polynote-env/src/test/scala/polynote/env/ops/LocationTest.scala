package polynote.env.ops

import org.scalatest.{FreeSpec, Matchers}

object TestInATopLevelModule {
  val loc: Location = Location.materialize
}

class LocationTest extends FreeSpec with Matchers {
  private val fileName = "LocationTest.scala"
  private val className = "polynote.env.ops.LocationTest"
  private def at(line: Int, method: String): Location = Location(fileName, line, method, className)

  def testInAMethod(): Location = {
    val loc = Location.materialize
    loc
  }

  def testAsAnImplicit(implicit loc: Location): Location = loc

  class TestInAnInnerClass {
    val topLoc: Location = Location.materialize

    def method(): Location = {
      val loc = Location.materialize
      loc
    }
  }

  object TestInAnInnerModule {
    val topLoc: Location = Location.materialize

    def method(): Location = {
      val loc = Location.materialize
      loc
    }
  }

  "in a block" in {
    val loc = Location.materialize
    loc shouldEqual at(40, "<unknown>")
  }

  "in a method" - {
    "inside the method" in {
      val loc = testInAMethod()
      loc shouldEqual at(15, "testInAMethod")
    }

    "as an implicit argument" in {
      val loc = testAsAnImplicit
      loc shouldEqual at(51, "<unknown>")
    }
  }

  "in an inner class" - {
    val inst = new TestInAnInnerClass
    "top level" in {
      inst.topLoc shouldEqual Location(fileName, 22, "<unknown>", s"$className.TestInAnInnerClass")
    }

    "method" in {
      inst.method() shouldEqual Location(fileName, 25, "method", s"$className.TestInAnInnerClass")
    }
  }

  "in a top-level module" in {
    TestInATopLevelModule.loc shouldEqual Location(fileName, 6, "<unknown>", "polynote.env.ops.TestInATopLevelModule")
  }

  "in an inner module" - {
    "top level" in {
      TestInAnInnerModule.topLoc shouldEqual Location(fileName, 31, "<unknown>", s"$className.TestInAnInnerModule")
    }

    "method" in {
      TestInAnInnerModule.method() shouldEqual Location(fileName, 34, "method", s"$className.TestInAnInnerModule")
    }
  }

}
