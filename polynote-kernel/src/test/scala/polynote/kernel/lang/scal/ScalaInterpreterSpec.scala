package polynote.kernel.lang.scal

import java.io.{ByteArrayOutputStream, OutputStream, PrintStream}

import cats.effect.IO
import fs2.Chunk
import fs2.concurrent.Queue
import org.scalatest._
import polynote.kernel.Output
import polynote.kernel.lang.KernelSpec
import polynote.kernel.util.QueueOutputStream

import scala.collection.mutable

class ScalaInterpreterSpec extends FlatSpec with Matchers with KernelSpec {

  "The scala kernel" should "be able to display html using the kernel runtime reference" in {
    val code = """kernel.display.html("hi")"""
    assertScalaOutput(code) { case (vars, output, displayed) =>
      val kernel = vars("kernel")
      kernel shouldEqual polynote.runtime.Runtime
      val (mime, html) = displayed.head
      mime shouldEqual "text/html"
      html shouldEqual "hi"
    }
  }

  it should "properly return vals declared by scala code" in {
    val code =
      """
        |val x = 1
        |val y = "foo"
        |class MyNewClass
        |val z = new MyNewClass()
        |val l = List(x, y, Map("sup?" -> "nm"), false)
        |val l2 = List(100, l)
        |val m = Map(x -> y, y -> 100, "hey!" -> l2)
        |val m2 = Map("hm" -> m, "humm" -> m)
      """.stripMargin
    assertScalaOutput(code) { case (vars, output, displayed) =>
      vars("x") shouldEqual 1
      vars("y") shouldEqual "foo"
      vars("z").toString should include("$notebook.Eval$test$2$MyNewClass")
      vars("l") shouldEqual List(1, "foo", Map("sup?" -> "nm"), false)
      vars("l2") shouldEqual List(100, List(1, "foo", Map("sup?" -> "nm"), false))
      vars("m") shouldEqual Map(1 -> "foo", "foo" -> 100, "hey!" ->  List(100, List(1, "foo", Map("sup?" -> "nm"), false)))
      vars("m2") shouldEqual Map(
        "hm" -> Map(1 -> "foo", "foo" -> 100, "hey!" ->  List(100, List(1, "foo", Map("sup?" -> "nm"), false))),
        "humm" -> Map(1 -> "foo", "foo" -> 100, "hey!" ->  List(100, List(1, "foo", Map("sup?" -> "nm"), false))))

      displayed shouldBe empty
    }
  }

  it should "assign a value to result of code if it ends in an expression" in {
    val code =
      """
        |val x = 1
        |val y = 2
        |x + y
      """.stripMargin
    assertScalaOutput(code) { (vars, output, displayed) =>
      vars("x") shouldEqual 1
      vars("y") shouldEqual 2
      vars("restest") shouldEqual 3
    }
  }

  it should "capture all output of the code" in {
    val code =
      """
        |val x = 1
        |val y = 2
        |println(s"$x + $y = ${x + y}")
        |val answer = x + y
      """.stripMargin
    assertScalaOutput(code) { case (vars, output, displayed) =>
      vars("x") shouldEqual 1
      vars("y") shouldEqual 2
      vars("answer") shouldEqual 3

      output should contain (Output("text/plain; rel=stdout", "1 + 2 = 3"))
    }

  }

  it should "not bother to return any value if the code just prints" in {
    val code =
      """
        |println("Do you like muffins?")
      """.stripMargin
    assertScalaOutput(code) { (vars, output, displayed) =>
      vars should have size 1
      vars("kernel") shouldEqual polynote.runtime.Runtime

      output should contain (Output("text/plain; rel=stdout", "Do you like muffins?"))
    }
  }
}
