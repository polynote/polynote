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
    val code =
      """
        |val x = 1
        |val y = new {}
        |val z = {
        |  val foo = 1
        |  foo
        |}
        |def f(i) = i + x
        |spark.sparkContext.parallelize(Seq(1,2,3)).map(_ + x).collect.toList
      """.stripMargin
    assertSparkScalaOutput(code) { case (vars, output, displayed) =>
      vars("kernel") shouldEqual polynote.runtime.Runtime
      vars("spark") shouldBe a[SparkSession]
      vars("x") shouldEqual 1
      vars("Out") shouldEqual List(2, 3, 4)
    }
  }
}

