package polynote.kernel.lang.python

import jep.python.{PyCallable, PyObject}
import org.scalatest._
import polynote.kernel.Output
import polynote.kernel.lang.KernelSpec
import polynote.runtime.MIMERepr
import polynote.runtime.python.{PythonFunction, PythonObject}


class PythonInterpreterSpec extends FlatSpec with Matchers with KernelSpec {

  "The python kernel" should "be able to display html using the kernel runtime reference" in {
    assertPythonOutput("kernel.display.html('hi')") { case (vars, output, displayed) =>
      vars.toSeq shouldBe empty
      output shouldBe empty
      displayed should contain only "text/html" -> "hi"
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
      //TODO: can we figure out a nicer way to test this?
      vars("x") shouldEqual 1
      vars("y") shouldEqual "foo"
      vars("A") shouldBe a [PythonFunction]
      vars("A").toString shouldEqual "<class '__main__.A'>"
      vars("z") shouldBe a [PythonObject]
      vars("z").toString should startWith("<__main__.A object")
      vars("d") shouldBe a [PythonObject]
      vars("d").toString shouldEqual "2019-02-03 00:00:00"
      vars("l") shouldEqual List(1, "foo", Map("sup?" -> "nm"), false)
      vars("l2") shouldEqual List(100, List(1, "foo", Map("sup?" -> "nm"), false))
      vars("m") shouldEqual Map(1 -> "foo", "foo" -> 100, "hey!" ->  List(100, List(1, "foo", Map("sup?" -> "nm"), false)))
      vars("m2") shouldEqual Map(
        "hm" -> Map(1 -> "foo", "foo" -> 100, "hey!" ->  List(100, List(1, "foo", Map("sup?" -> "nm"), false))),
        "humm" -> Map(1 -> "foo", "foo" -> 100, "hey!" ->  List(100, List(1, "foo", Map("sup?" -> "nm"), false))))

      output shouldBe empty
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
      vars.toSeq should contain theSameElementsAs Seq(
        "x" -> 1,
        "y" -> 2,
        "Out" -> 3
      )

      output shouldBe empty
      displayed shouldBe empty
    }
  }

  it should "capture all output of the python code" in {
    val code =
      """
        |x = 1
        |y = 2
        |print("{} + {} = {}".format(x, y, x + y))
        |answer = x + y
        |answer
      """.stripMargin
    assertPythonOutput(code) { case (vars, output, displayed) =>
      vars.toSeq should contain theSameElementsAs Seq(
        "x" -> 1,
        "y" -> 2,
        "answer" -> 3,
        "Out" -> 3
      )

      output should contain theSameElementsAs Seq(
        Output("text/plain; rel=stdout", "1 + 2 = 3")
      )
      displayed shouldBe empty
    }

  }

  it should "not bother to return any value if the python code just prints" in {
    val code =
      """
        |print("Do you like muffins?")
      """.stripMargin
    assertPythonOutput(code) { case (vars, output, displayed) =>
      vars.toSeq shouldBe empty

      output should contain  only Output("text/plain; rel=stdout", "Do you like muffins?")
      displayed shouldBe empty
    }
  }

  it should "not error when the cell contains no statements" in {
    val code =
      "# this is just a comment"

    assertPythonOutput(code) {
      case (vars, output, displayed) =>
        output shouldBe empty
        displayed shouldBe empty
    }
  }

  it should "return a useful PythonObject when a value can't be converted to the JVM directly" in {
    val code =
      """class Tester:
        |  def __init__(self, wizzle):
        |    self.wizzle = wizzle
        |
        |  def foo(self, num):
        |    return num + 10
        |
        |  def bar(self, aa, bb, cc):
        |    return aa + bb + cc
        |
        |foo = Tester("wozzle")
      """.stripMargin

    assertPythonOutput(code) {
      case (vars, _, _) =>
        val foo = vars("foo").asInstanceOf[PythonObject]
        foo.wizzle[String] shouldEqual "wozzle"
        val wizzleAsObject = foo.wizzle
        wizzleAsObject shouldBe a [PythonObject]
        wizzleAsObject.toString shouldEqual "wozzle"

        foo.foo(10) shouldEqual 20

        foo.bar(1, 2, 3) shouldEqual 6
        foo.bar(1, bb = 2, cc = 3) shouldEqual 6
        foo.bar(aa = 1, bb = 2, cc = 3) shouldEqual 6

        foo.wizzle = "wizzlewozzleweasel"
        foo.wizzle[String] shouldEqual "wizzlewozzleweasel"
    }
  }

  it should "return a constructor function of PyObject for Python constructors" ignore {
    // TODO: we need to fix the PyCallable interface upstream in Jep for this to be possible, so test is ignored for now
    val code =
      """class Tester:
        |  def __init__(self, wizzle):
        |    self.wizzle = wizzle
        |
        |  def foo(self, num):
        |    return num + 10
        |
        |  def bar(self, aa, bb, cc):
        |    return aa + bb + cc
        |
        |foo = Tester("wozzle")
      """.stripMargin

    assertPythonOutput(code) {
      case (vars, _, _) =>
        val Tester = vars("Tester").asInstanceOf[PythonFunction]
        val tester = Tester("weasel")
        tester shouldBe a[PythonObject]

        tester.asInstanceOf[PythonObject].wizzle[String] shouldEqual "weasel"

        val tester2 = Tester(wizzle = "wozzle")
        tester.asInstanceOf[PythonObject].wizzle[String] shouldEqual "wozzle"
    }
  }

  "PythonObject" should "provide reprs from the __repr__, _repr_html_, and _repr_latex_ methods if they exist" in {
    val code =
      """class Example:
        |  def __init__(self):
        |    return
        |
        |  def __repr__(self):
        |    return "Plaintext string"
        |
        |  def _repr_html_(self):
        |    return "<h1>HTML string</h1>"
        |
        |  def _repr_latex_(self):
        |    return "latex{string}"
        |
        |test = Example()""".stripMargin

    assertPythonOutput(code) {
      case (vars, _, _) =>
        val test = vars("test").asInstanceOf[PythonObject]
        PythonObject.defaultReprs(test).toList should contain theSameElementsAs List(
          MIMERepr("text/plain", "Plaintext string"),
          MIMERepr("text/html", "<h1>HTML string</h1>"),
          MIMERepr("application/latex", "latex{string}")
        )
    }
  }

  it should "not cause an error if any of those methods don't exist" in {
    val code =
      """class Example:
        |  def __init__(self):
        |    return
        |
        |  def __repr__(self):
        |    return "Plaintext string"
        |
        |  def _repr_html_(self):
        |    return "<h1>HTML string</h1>"
        |
        |test = Example()""".stripMargin

    assertPythonOutput(code) {
      case (vars, _, _) =>
        val test = vars("test").asInstanceOf[PythonObject]
        PythonObject.defaultReprs(test).toList should contain theSameElementsAs List(
          MIMERepr("text/plain", "Plaintext string"),
          MIMERepr("text/html", "<h1>HTML string</h1>")
        )
    }
  }

  "PythonFunction" should "allow positional and keyword args" in {

    val code =
      """def hello(one, two, three):
        |  return one + two + three""".stripMargin

    assertPythonOutput(code) {
      case (vars, _, _) =>
        val hello = vars("hello").asInstanceOf[PythonFunction]
        val posArgs = hello(1, 2, 3)
        posArgs shouldEqual 6

        val kwArgs = hello(one = 1, two = 2, three = 3)
        kwArgs shouldEqual 6

        val mixed = hello(1, 2, three = 3)
        mixed shouldEqual 6
    }
  }
}
