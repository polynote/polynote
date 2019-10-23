package polynote.kernel.interpreter.python

import org.scalatest.{FreeSpec, Matchers}
import polynote.kernel.{CompileErrors, Completion, CompletionType, KernelReport, Output, Pos, Result, ScalaCompiler}
import polynote.kernel.interpreter.State
import polynote.messages.TinyList
import polynote.runtime.MIMERepr
import polynote.runtime.python.{PythonFunction, PythonObject}
import polynote.testing.kernel.MockEnv
import polynote.testing.{InterpreterSpec, ZIOSpec}
import zio.ZIO
import zio.interop.catz._

class PythonInterpreterSpec extends FreeSpec with Matchers with InterpreterSpec {

  val interpreter: PythonInterpreter = PythonInterpreter(None).provide(ScalaCompiler.Provider.of(compiler)).runIO()
  interpreter.init(State.Root).provideSomeM(MockEnv(-1)).runIO()

  "PythonInterpreter" - {
    "properly return vars declared by python code" in {
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
      """.stripMargin
      assertOutput(code) {
        case (vars, output) =>
          //TODO: can we figure out a nicer way to test this?
          vars("x") shouldEqual 1
          vars("y") shouldEqual "foo"
          vars("A") shouldBe a[PythonFunction]
          vars("A").toString shouldEqual "<class '__main__.A'>"
          vars("z") shouldBe a[PythonObject]
          vars("z").toString should startWith("<__main__.A object")
          vars("d") shouldBe a[PythonObject]
          vars("d").toString shouldEqual "2019-02-03 00:00:00"
          vars("l") match {
            case l: PythonObject => l.asScalaList.map(_.asInstanceOf[PythonObject]) match {
              case i :: s :: m :: b :: Nil =>
                i.as[Integer].intValue() shouldEqual 1
                s.as[String] shouldEqual "foo"
                m.asScalaMapOf[String, String] shouldEqual Map("sup?" -> "nm")
                b.as[java.lang.Boolean] shouldEqual false
              case other => fail(s"Python=>Scala list has unexpected result $other")
            }
            case other => fail(s"Expected PythonObject, found $other")
          }

          vars("l2") match {
            case l2: PythonObject => l2.asScalaList.map(_.asInstanceOf[PythonObject]) match {
              case i :: l :: Nil =>
                i.as[Integer] shouldEqual 100
                l.asScalaList.map(_.asInstanceOf[PythonObject]) match {
                  case i :: s :: m :: b :: Nil =>
                    i.as[Integer] shouldEqual 1
                    s.as[String] shouldEqual "foo"
                    m.asScalaMapOf[String, String] shouldEqual Map("sup?" -> "nm")
                    b.as[java.lang.Boolean] shouldEqual false
                  case other => fail(s"Python=>Scala list has unexpected result $other")
                }
              case other => fail(s"Python=>Scala list has unexpected result $other")
            }
          }

          output shouldBe empty
      }
    }

    "assign a value to result of python code if it ends in an expression" in {
      val code =
        """
          |x = 1
          |y = 2
          |x + y
      """.stripMargin
      assertOutput(code) {
        case (vars, output) =>
          vars.toSeq should contain theSameElementsAs Seq(
            "x" -> 1,
            "y" -> 2,
            "Out" -> 3
          )

          output shouldBe empty
      }
    }

    "capture all output of the python code" in {
      val code =
        """
          |x = 1
          |y = 2
          |print("{} + {} = {}".format(x, y, x + y))
          |answer = x + y
          |answer
      """.stripMargin
      assertOutput(code) {
        case (vars, output) =>
          vars.toSeq should contain theSameElementsAs Seq(
            "x" -> 1,
            "y" -> 2,
            "answer" -> 3,
            "Out" -> 3
          )

          stdOut(output) shouldEqual "1 + 2 = 3\n"
      }

    }

    "not bother to return any value if the python code just prints" in {
      val code =
        """
          |print("Pssst! Do you like muffins?")
          |print("Yeah, I guess so")
          |print("What kind of muffins?")
          |print("Uh, blueberry muffins are pretty good...")
      """.stripMargin
      assertOutput(code) {
        case (vars, output) =>
          vars.toSeq shouldBe empty

          stdOut(output) shouldEqual
            """Pssst! Do you like muffins?
              |Yeah, I guess so
              |What kind of muffins?
              |Uh, blueberry muffins are pretty good...
              |""".stripMargin

      }
    }

    "not error when the cell contains no statements" in {
      val code =
        "# this is just a comment"

      assertOutput(code) {
        case (vars, output) =>
          vars shouldBe empty
          output shouldBe empty
      }
    }

    "not error when the cell contains an empty print" in {
      val code = "print('')"

      assertOutput(code) {
        case (vars, output) =>
          stdOut(output) shouldEqual "\n"
      }
    }

    "return a useful PythonObject when a value can't be converted to the JVM directly" in {
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

      assertOutput(code) {
        case (vars, _) =>
          val foo = vars("foo").asInstanceOf[PythonObject]
          foo.wizzle[String] shouldEqual "wozzle"
          val wizzleAsObject = foo.wizzle
          wizzleAsObject shouldBe a[PythonObject]
          wizzleAsObject.toString shouldEqual "wozzle"

          foo.foo(10).as[Integer] shouldEqual 20

          foo.bar(1, 2, 3).as[Integer] shouldEqual 6
          foo.bar(1, bb = 2, cc = 3).as[Integer] shouldEqual 6
          foo.bar(aa = 1, bb = 2, cc = 3).as[Integer] shouldEqual 6

          foo.wizzle = "wizzlewozzleweasel"
          foo.wizzle[String] shouldEqual "wizzlewozzleweasel"
      }
    }

    "return a constructor function of PyObject for Python constructors" in {
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

      assertOutput(code) {
        case (vars, _) =>
          val Tester = vars("Tester").asInstanceOf[PythonFunction]
          val tester = Tester("weasel")
          tester shouldBe a[PythonObject]

          tester.asInstanceOf[PythonObject].wizzle[String] shouldEqual "weasel"

          val tester2 = Tester(wizzle = "wozzle")
          tester2.asInstanceOf[PythonObject].wizzle[String] shouldEqual "wozzle"
      }
    }

    "Raise SyntaxErrors as CompileErrors" in {
      a[CompileErrors] should be thrownBy {
        interp("syntax error").run(State.id(1)).runIO()
      }
    }

    "properly handle imports in local scopes" in {
      assertOutput(
        """
          |import math
          |
          |def func(x):
          |    result = math.sin(x)  # create a local var to make sure it doesn't appear in the outputs
          |    return result
          |
          |func(math.pi/2)""".stripMargin) {
        case (vars, output) =>
          vars should have size 2
          val f = vars("func")
          f shouldBe a[PythonFunction]
          val fInstance = f.asInstanceOf[PythonFunction]
          fInstance(Math.PI/2).as[java.lang.Number] shouldEqual 1.0
          vars("Out") shouldEqual 1.0
          output shouldBe empty
      }
    }

    "completions" in {
      val completions = interpreter.completionsAt("dela", 4, State.id(1)).runIO()
      completions shouldEqual List(Completion("delattr", Nil, TinyList(List(TinyList(List(("o", ""), ("name", ""))))), "", CompletionType.Method))
    }
  }

  "PythonObject" - {
    "provide reprs from the __repr__, _repr_html_, and _repr_latex_ methods if they exist" in {
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

      assertOutput(code) {
        case (vars, _) =>
          val test = vars("test").asInstanceOf[PythonObject]
          PythonObject.defaultReprs(test).toList should contain theSameElementsAs List(
            MIMERepr("text/plain", "Plaintext string"),
            MIMERepr("text/html", "<h1>HTML string</h1>"),
            MIMERepr("application/x-latex", "latex{string}")
          )
      }
    }

    "not cause an error if any of those methods don't exist" in {
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

      assertOutput(code) {
        case (vars, _) =>
          val test = vars("test").asInstanceOf[PythonObject]
          PythonObject.defaultReprs(test).toList should contain theSameElementsAs List(
            MIMERepr("text/plain", "Plaintext string"),
            MIMERepr("text/html", "<h1>HTML string</h1>")
          )
      }
    }
  }

  "PythonFunction" - {
    "allow positional and keyword args" in {

      val code =
        """def hello(one, two, three):
          |  return one + two + three""".stripMargin

      assertOutput(code) {
        case (vars, _) =>
          val hello = vars("hello").asInstanceOf[PythonFunction]
          val posArgs = hello(1, 2, 3).as[Integer]
          posArgs shouldEqual 6

          val kwArgs = hello(one = 1, two = 2, three = 3).as[Integer]
          kwArgs shouldEqual 6

          val mixed = hello(1, 2, three = 3).as[Integer]
          mixed shouldEqual 6
      }
    }
  }

}
