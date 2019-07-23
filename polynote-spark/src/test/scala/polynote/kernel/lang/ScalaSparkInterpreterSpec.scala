package polynote.kernel.lang

import org.apache.spark.sql.SparkSession
import org.scalatest.{FlatSpec, Matchers}
import polynote.kernel.SparkKernelSpec

class ScalaSparkInterpreterSpec extends FlatSpec with Matchers with SparkKernelSpec {

  "The Scala Spark Kernel" should "properly run simple spark jobs" in {
    val code =
      """
        |val x = 1
        |spark.sparkContext.parallelize(Seq(1,2,3)).map(_ + x).collect.toList
      """.stripMargin
    assertSparkScalaOutput(code) { case (vars, output, displayed) =>
        vars("kernel") shouldEqual polynote.runtime.Runtime
        vars("spark") shouldBe a[SparkSession]
        vars("x") shouldEqual 1
        vars("Out") shouldEqual List(2, 3, 4)
    }
  }

  it should "not be affected by unserializable values stored in the Runtime" in {
    val code =
      """
        |val x = 1
        |kernel.putValue("notSerializable", new {})
        |spark.sparkContext.parallelize(Seq(1,2,3)).map(_ + x).collect.toList
      """.stripMargin
    assertSparkScalaOutput(code) { case (vars, output, displayed) =>
      vars("kernel") shouldEqual polynote.runtime.Runtime
      vars("spark") shouldBe a[SparkSession]
      vars("x") shouldEqual 1
      vars("Out") shouldEqual List(2, 3, 4)
    }
  }

  it should "not be affected by unused unserializable values" in {
    val code = Seq(
      "def banana: Int = 1",
      "val foo = banana",
      "implicit val i: Int = 10",
      """
        |val x = 100
        |val y = new {}
        |val z = {
        |  val foo = 1000
        |  foo
        |}
        |val implicitResult = implicitly[Int]
      """.stripMargin,
      "spark.sparkContext.parallelize(Seq(1,2,3)).map(_ + x).collect.toList"
    )
    assertSparkScalaOutput(code) { case (vars, output, displayed) =>
      vars("kernel") shouldEqual polynote.runtime.Runtime
      vars("spark") shouldBe a[SparkSession]
      vars("foo") shouldEqual 1
      vars("i") shouldEqual 10
      vars("x") shouldEqual 100
      vars("z") shouldEqual 1000
      vars("implicitResult") shouldEqual 10
      vars("Out") shouldEqual List(101, 102, 103)
    }
  }

  it should "allow values to be overridden" in {
    val code = Seq(
      "val a: Int = 100",
      "val a: Int = 200",
      "val b = a"
    )
    assertSparkScalaOutput(code) { case (vars, output, displayed) =>
      vars("kernel") shouldEqual polynote.runtime.Runtime
      vars("spark") shouldBe a[SparkSession]
      vars("a") shouldEqual 200
      vars("b") shouldEqual 200
    }
  }

  it should "work with implicits" in {
    val code = Seq(
      "implicit val a: Int = 100",
      "implicit val a: Int = 200", // check implicit override
      """
        |def foo(a: Int)(implicit s: Int) = a + s
        |val b = foo(1)
      """.stripMargin)
    assertSparkScalaOutput(code) { case (vars, output, displayed) =>
      vars("kernel") shouldEqual polynote.runtime.Runtime
      vars("spark") shouldBe a[SparkSession]
      vars("a") shouldEqual 200
      vars("b") shouldEqual 201
    }
  }

  it should "work with class defs" in {
    val code = Seq(
      """
        |case class Foo(i: Int)
        |val fooInstance = Foo(1)
        |""".stripMargin,
      """class Bar(j: Int) {
        |  def i: Int = j
        |}
      """.stripMargin,
      "abstract class Baz(val i: Int)",
      "class BazImpl(i: Int) extends Baz(i)",
      """trait Quux {
        |  def i: Int
        |  def j: Int = 1000
        |}""".stripMargin,
      """class QuuxImpl extends Quux {
        |  override def i: Int = 100
        |}
      """.stripMargin,
      """
        |case class Beep(s: String)
        |object Beep {
        |  def apply(i: Int): Beep = Beep(i.toString)
        |}
        |""".stripMargin,
      "object Bop { val i = Foo(4).i + 1 }",
      """
        |val foo = Foo(0).i + fooInstance.i
        |val bar = new Bar(2).i
        |val baz = new BazImpl(3).i
        |val quux = new QuuxImpl()
        |val quuxI = quux.i
        |val quuxJ = quux.j
        |val beep1 = Beep("four").s
        |val beep2 = Beep(4).s
        |val bop = Bop.i
        |""".stripMargin)
    assertSparkScalaOutput(code) { case (vars, output, displayed) =>
      vars("kernel") shouldEqual polynote.runtime.Runtime
      vars("spark") shouldBe a[SparkSession]
      vars("foo") shouldEqual 1
      vars("bar") shouldEqual 2
      vars("baz") shouldEqual 3
      vars("quuxI") shouldEqual 100
      vars("quuxJ") shouldEqual 1000
      vars("beep1") shouldEqual "four"
      vars("beep2") shouldEqual "4"
      vars("bop") shouldEqual 5
    }
  }

  it should "work with lazy vals and vars" in {
    val code = Seq(
      "var a = 100",
      "lazy val b = a"
    )
    assertSparkScalaOutput(code) { case (vars, output, displayed) =>
      vars("kernel") shouldEqual polynote.runtime.Runtime
      vars("spark") shouldBe a[SparkSession]
      vars("a") shouldEqual 100
      vars("b") shouldEqual 100
    }
  }

  it should "work with functions that have default values" in {
    val code = Seq(
      """def foo(a: Int, b: Int = 1) = a + b
        |foo(1)
      """.stripMargin,
      "val a = foo(2, 3)",
      "val b = foo(2)"
    )
    assertSparkScalaOutput(code) { case (vars, output, displayed) =>
      vars("Out") shouldEqual 2
      vars("a") shouldEqual 5
      vars("b") shouldEqual 3
    }
  }
}

