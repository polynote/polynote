package polynote.kernel.lang

import org.apache.spark.sql.SparkSession
import org.scalatest.{FlatSpec, Matchers}
import polynote.kernel.{RuntimeError, SparkKernelSpec}

class ScalaSparkInterpreterSpec extends FlatSpec with Matchers with SparkKernelSpec {

  "The Scala Spark Kernel" should "properly run simple spark jobs" in {
    val code =
      """
        |val x = 1
        |val y = this
        |//spark.sparkContext.parallelize(Seq(1,2,3)).map(_ + x).collect.toList
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
      "implicit val i: Int = 100",
      """
        |val x = 1
        |val y = new {}
        |val z = {
        |  val foo = 1
        |  foo
        |}
        |val implicitResult = implicitly[Int]
        |spark.sparkContext.parallelize(Seq(1,2,3)).map(_ + x).collect.toList
      """.stripMargin)
    assertSparkScalaOutput(code) { case (vars, output, displayed) =>
      vars("kernel") shouldEqual polynote.runtime.Runtime
      vars("spark") shouldBe a[SparkSession]
      vars("x") shouldEqual 1
      vars("z") shouldEqual 1
      vars("implicitResult") shouldEqual 100
      vars("Out") shouldEqual List(2, 3, 4)
    }
  }

  it should "work with implicits" in {
    val code = Seq(
      "implicit val a: Int = 100",
//      "implicit val b: Int = 200", // check implicit override
      """
        |def foo(a: Int)(implicit s: Int) = a + s
        |val c = foo(1)
      """.stripMargin)
    assertSparkScalaOutput(code) { case (vars, output, displayed) =>
      vars("kernel") shouldEqual polynote.runtime.Runtime
      vars("spark") shouldBe a[SparkSession]
      vars("a") shouldEqual 100
//      vars("b") shouldEqual 200
      vars("c") shouldEqual 101
    }
  }

}

