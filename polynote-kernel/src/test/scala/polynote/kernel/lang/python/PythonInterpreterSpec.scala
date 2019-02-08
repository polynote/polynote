package polynote.kernel.lang.python

import jep.python.{PyCallable, PyObject}
import org.scalatest._
import polynote.kernel.Output
import polynote.kernel.lang.KernelSpec

import scala.collection.mutable


class PythonInterpreterSpec extends FlatSpec with Matchers with KernelSpec {

  "The python kernel" should "be able to display html using the kernel runtime reference" in {
    assertPythonOutput("kernel.display.html('hi')") { case (vars, output, displayed) =>
      val kernel = vars("kernel")
      kernel shouldEqual polynote.runtime.Runtime
      val (mime, html) = displayed.head
      mime shouldEqual "text/html"
      html shouldEqual "hi"
    }
  }

  it should "properly return vars declared by python code" in {
    val code =
      """
        |x = 1
        |y = "foo"
        |class A(object):
        |    pass
        |z = A()
        |import datetime
        |d = datetime.datetime(2019, 2, 3, 00, 00)
        |l = [x, y, {"sup?": "nm"}, False]
        |l2 = [100, l]
        |m = {x: y, y: 100, 'hey!': l2}
        |m2 = {'hm': m, 'humm': m}
      """.stripMargin
    assertPythonOutput(code) { case (vars, output, displayed) =>
      vars("x") shouldEqual 1
      vars("y") shouldEqual "foo"
      vars("A") shouldBe a[PyCallable]
      vars("A").toString shouldEqual "<class '__main__.A'>"
      vars("z") shouldBe a[PyObject]
      vars("z").toString should startWith("<__main__.A object")
      vars("d") shouldBe a[PyObject]
      vars("d").toString shouldEqual "2019-02-03 00:00:00"
      vars("l") shouldEqual List(1, "foo", Map("sup?" -> "nm"), false)
      vars("l2") shouldEqual List(100, List(1, "foo", Map("sup?" -> "nm"), false))
      vars("m") shouldEqual Map(1 -> "foo", "foo" -> 100, "hey!" ->  List(100, List(1, "foo", Map("sup?" -> "nm"), false)))
      vars("m2") shouldEqual Map(
        "hm" -> Map(1 -> "foo", "foo" -> 100, "hey!" ->  List(100, List(1, "foo", Map("sup?" -> "nm"), false))),
        "humm" -> Map(1 -> "foo", "foo" -> 100, "hey!" ->  List(100, List(1, "foo", Map("sup?" -> "nm"), false))))

      displayed shouldBe empty
    }
  }

  it should "assign a value to result of python code if it ends in an expression" in {
    val code =
      """
        |x = 1
        |y = 2
        |x + y
      """.stripMargin
    assertPythonOutput(code) { case (vars, output, displayed) =>
      vars("x") shouldEqual 1
      vars("y") shouldEqual 2
      vars("restest") shouldEqual 3

//      displayed shouldEqual mutable.ArrayBuffer("text/plain; lang=python" -> "restest = 3")
    }
  }

  it should "capture all output of the python code" in {
    val code =
      """
        |x = 1
        |y = 2
        |print("{} + {} = {}".format(x, y, x + y))
        |answer = x + y
      """.stripMargin
    assertPythonOutput(code) { case (vars, output, displayed) =>
      vars("x") shouldEqual 1
      vars("y") shouldEqual 2
      vars("answer") shouldEqual 3

      output should contain (Output("text/plain; rel=stdout", "1 + 2 = 3"))
    }

  }

  it should "not bother to return any value if the python code just prints" in {
    val code =
      """
        |print("Do you like muffins?")
      """.stripMargin
    assertPythonOutput(code) { case (vars, output, displayed) =>
      vars should have size 1
      vars("kernel") shouldEqual polynote.runtime.Runtime

      output should contain (Output("text/plain; rel=stdout", "Do you like muffins?"))
    }
  }
}
