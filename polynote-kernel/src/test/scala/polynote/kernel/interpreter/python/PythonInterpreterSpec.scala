package polynote.kernel.interpreter.python

import jep.python.PyCallable
import org.scalatest.{FreeSpec, Matchers}
import polynote.kernel.interpreter.State
import polynote.kernel.{CompileErrors, Completion, CompletionType, ParameterHint, ParameterHints, ScalaCompiler, Signatures}
import polynote.messages.TinyList
import polynote.runtime.MIMERepr
import polynote.runtime.python.{PythonFunction, PythonObject}
import polynote.testing.InterpreterSpec
import polynote.testing.kernel.MockEnv
import zio.interop.catz._

class PythonInterpreterSpec extends FreeSpec with Matchers with InterpreterSpec {

  val interpreter: PythonInterpreter = PythonInterpreter(None).provide(ScalaCompiler.Provider.of(compiler)).runIO()
  interpreter.init(State.Root).provideSomeLayer(MockEnv.init).runIO()

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
          vars("A").toString shouldEqual "<class 'A'>"
          vars("z") shouldBe a[PythonObject]
          vars("z").toString should startWith("<A object")
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

    "properly handle variables defined inside nested scopes" in {
      assertOutput(
        """
          |a = 1
          |result = 0
          |if True:
          |    b = 1
          |    a = 2
          |    result = max([x for x in [1,2,3,4,5,6] if x < a + b])
          |
          |result
          |""".stripMargin) {
        case (vars, output) =>
          vars should have size 4
          vars("a") shouldEqual 2
          vars("b") shouldEqual 1
          vars("result") shouldEqual 2
          vars("Out") shouldEqual 2
          output shouldBe empty
      }
    }

    "properly handle dataclasses" in {
      assertOutput(
        """
          |from dataclasses import dataclass
          |
          |class Foo(object):
          |    pass
          |
          |@dataclass
          |class Bar:
          |    my_foo: Foo
          |
          |foo = Foo()
          |bar = Bar(foo)
          |[bar, bar.my_foo]
          |""".stripMargin) {
        case (vars, output) =>
          vars should have size 5

          val fooCls = vars("Foo")
          fooCls shouldBe a[PythonFunction]
          val Foo = fooCls.asInstanceOf[PythonFunction]
          val z = Foo.runner.typeName(Foo)
          val z2 = Foo.runner.isCallable(Foo.unwrap)
          val fooInst = Foo()
          val u = fooInst.runner.typeName(fooInst)
          val u2 = fooInst.runner.isCallable(fooInst.unwrap)
          val x = fooInst.__class__
          val y = x.runner.typeName(x)
          val y2 = x.runner.isCallable(x.unwrap)
          fooInst.__class__ shouldEqual fooCls
          fooInst.__class__.__name__[String] shouldEqual "Foo"

          val barCls = vars("Bar")
          barCls shouldBe a[PythonFunction]
          val Bar = barCls.asInstanceOf[PythonFunction]
          val barInst = Bar(fooInst)
          barInst.__class__ shouldEqual barCls
          barInst.__class__.__name__[String] shouldEqual "Bar"

          val foo = vars("foo")
          foo shouldBe a[PythonObject]
          foo.asInstanceOf[PythonObject].__class__ shouldEqual fooCls
          foo.asInstanceOf[PythonObject].__class__.__name__[String] shouldEqual "Foo"

          val bar = vars("bar")
          bar shouldBe a[PythonObject]
          bar.asInstanceOf[PythonObject].__class__ shouldEqual barCls
          bar.asInstanceOf[PythonObject].__class__.__name__[String] shouldEqual "Bar"
          bar.asInstanceOf[PythonObject].my_foo shouldEqual foo

          val out = vars("Out")
          out shouldBe a[PythonObject]
          val List(outBar, outFoo) = out.asInstanceOf[PythonObject].asScalaList
          outBar shouldEqual bar
          outFoo shouldEqual foo
          outBar.my_foo shouldEqual outFoo

          output shouldBe empty
      }
    }

    "completions" in {
      val completions = interpreter.completionsAt("dela", 4, State.id(1)).runIO()
      completions shouldEqual List(Completion("delattr", Nil, TinyList(List(TinyList(List(("o", ""), ("name", "str"))))), "", CompletionType.Method))
      val keywordCompletion = interpreter.completionsAt("d={'foo': 'bar'}; d['']", 21, State.id(1)).runIO()
      keywordCompletion shouldEqual List(Completion("'foo", Nil, Nil, "", CompletionType.Unknown, None))
    }

    "parameters" in {
      val params = interpreter.parametersAt("delattr(", 8, State.id(1)).runIO()
      params shouldEqual Option(Signatures(List(
        ParameterHints("delattr(o, name: str)", Option("Deletes the named attribute from the given object."),
          List(ParameterHint("o", "", None), ParameterHint("name", "str", None)))),0,0))
    }
  }

  "PythonObject" - {
    "provide reprs from __repr__, _repr_*_, and _repr_mimebundle_ methods if they exist" in {
      val code =
        """class Example(object):
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
          |    return "$latex{string}$"
          |
          |  def _repr_svg_(self):
          |    return "<svg />"
          |
          |  def _repr_jpeg_(self):
          |    return ("somekindofbase64encodedjpeg", {'height': 400 })
          |
          |  def _repr_png_(self):
          |    return "iguessabase64encodedpng"
          |
          |  def _repr_mimebundle_(self, include=None, exclude=None):
          |    return { "application/x-blahblah": "blahblah" }
          |
          |test = Example()""".stripMargin

      assertOutput(code) {
        case (vars, _) =>
          val test = vars("test").asInstanceOf[PythonObject]
          PythonObject.defaultReprs(test).toList should contain theSameElementsAs List(
            MIMERepr("text/plain", "Plaintext string"),
            MIMERepr("text/html", "<h1>HTML string</h1>"),
            MIMERepr("application/x-latex", "latex{string}"),
            MIMERepr("image/svg+xml", "<svg />"),
            MIMERepr("image/jpeg", "somekindofbase64encodedjpeg"),
            MIMERepr("image/png", "iguessabase64encodedpng"),
            MIMERepr("application/x-blahblah", "blahblah")
          )
      }
    }

    "handle case where _repr_mimebundle_ returns a tuple" in {
      val code =
        """class Example(object):
          |  def __init__(self):
          |    return
          |
          |  def __repr__(self):
          |    return "Plaintext string"
          |
          |  def _repr_mimebundle_(self, include=None, exclude=None):
          |    return ({ "application/x-blahblah": "blahblah" }, {})
          |
          |test = Example()""".stripMargin

      assertOutput(code) {
        case (vars, _) =>
          val test = vars("test").asInstanceOf[PythonObject]
          PythonObject.defaultReprs(test).toList should contain theSameElementsAs List(
            MIMERepr("text/plain", "Plaintext string"),
            MIMERepr("application/x-blahblah", "blahblah")
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

    "capture error causes if present" in {
      val code =
        """
          |class FooException(BaseException):
          |    pass
          |
          |class BarException(BaseException):
          |    pass
          |
          |try:
          |    raise FooException("first")
          |except:
          |    try:
          |        raise BarException("second")
          |    except:
          |        raise Exception("third")
          |
          |""".stripMargin
      try {
        assertOutput(code) { case _ => }
      } catch {
        case err: Throwable =>
          err.getMessage shouldEqual "Exception: third"
          err.getCause.getMessage shouldEqual "BarException: second"
          err.getCause.getCause.getMessage shouldEqual "FooException: first"
          err.getCause.getCause.getCause shouldEqual null
      }
    }

    // TODO: need to shut down the interpreter in order to see this result.
    //       should refactor the interpreter tests to start/stop the interpreter every time, allowing hooks to be set
    //       before and after initialization.
    "supports registration of exit handlers" in {
      val code =
        """
          |import atexit, sys
          |def exit_fun():
          |    print("ran exit function!")
          |
          |atexit.register(exit_fun)
          |""".stripMargin
      assertOutput(code) {
        case (vars, out) =>
          val x = vars
          val y = out
          val z = 1
      }
    }

    "doesn't pollute namespace with imports" in {
      val code =
        """
          |import sys
          |
          |args = sys.argv
          |
          |from os import *
          |c = curdir
          |
          |""".stripMargin
      assertOutput(code) {
        case (vars, out) =>
          vars should have size 2
          vars("args").asInstanceOf[PythonObject].asScalaList.map(_.as[String]) shouldEqual List("")
          vars("c") shouldEqual "."
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

  "DelegatingFinder"  - {
    // create a dummy finder which just translates a module path `<base>.<pkg>foo` to `foo` and just imports that.
    val defDummyFinder =
      """
        |import sys
        |from importlib.machinery import ModuleSpec
        |from types import ModuleType
        |
        |class DummyFinder(object):
        |    def __init__(self, base, pkg):
        |        self.base = base
        |        self.pkg = pkg
        |        self.prefix = f"{base}.{pkg}"
        |
        |    def load_module(self, fullname):
        |        if fullname == self.base:
        |            s = ModuleType(self.base)
        |            # this is how the Finder "takes ownership" of the search path
        |            s.__path__ = [self.prefix]
        |            sys.modules[s.__name__] = s
        |
        |        return sys.modules.get(fullname)
        |
        |    def find_spec(self, fullname, path=None, target=None):
        |        if fullname == self.base:
        |            m = ModuleSpec(self.base, self)
        |            m.submodule_search_locations = self.prefix
        |            return m
        |
        |        if fullname.startswith(self.prefix) and self.prefix in path:
        |            name = fullname[len(self.prefix):]
        |            return ModuleSpec(name, self)
        |
        |        return None
        |""".stripMargin

    // utility to remove DelegatingFinder from the meta path
    val removeDelegatingFinder =
      """
        |import sys
        |sys.meta_path = list(filter(lambda f: "DelegatingFinder" not in str(f), sys.meta_path))
        |""".stripMargin

    // utility to add DelegatingFinder to the meta path without needing to reinitialize everything
    val addDelegatingFinder =
      """
        |if all("DelegatingFinder" not in f.__class__.__name__ for f in sys.meta_path):
        |    sys.meta_path.insert(0, DelegatingFinder())
        |""".stripMargin

    "should be able to import packages even if they share the same prefix but come from different importers" in {
      // For this test we'll have two dummy finders who share a namespace.
      // The idea is that without the DelegatingFinder the paths provided to the find_module call will conflict.

      // first, demonstrate that two DummyFinders will conflict without DelegatingFinder
      val sharedDummies =
        """
          |sys.meta_path.append(DummyFinder("shared", "dummy1"))
          |sys.meta_path.append(DummyFinder("shared", "dummy2"))
          |
          |from shared.dummy1datetime import datetime
          |print(datetime)
          |from shared.dummy2datetime import datetime
          |print(datetime)
          |""".stripMargin

      try {
        assertOutput(removeDelegatingFinder + defDummyFinder + sharedDummies) { case _ => }
      } catch {
        case e: RuntimeException =>
          e.getMessage shouldEqual "ModuleNotFoundError: No module named 'shared.dummy2datetime'"
      }

      assertOutput(addDelegatingFinder + defDummyFinder + sharedDummies) {
        case (vars, output) =>
          vars should have size 1
          val Array(datetime1, datetime2) = stdOut(output).split("\n")
          datetime1 shouldEqual "<class 'datetime.datetime'>"
          datetime2 shouldEqual "<class 'datetime.datetime'>"
      }
    }

    "should be able to import python packages even if they conflict with Java packages" in {
      // In this test, we'll create a DummyFinder who shares a namespace with the Jep importer

      val conflictingDummy =
        """
          |sys.meta_path.append(DummyFinder("java", "dummy1"))
          |
          |import java
          |print(java)
          |try:
          |    from java.dummy1datetime import datetime
          |    print(datetime)
          |except:
          |    # clean up this module because jep steals it!
          |    del sys.modules["java.dummy1datetime"]
          |    raise
          |import java.dummy1sys
          |print(java.dummy1sys)
          |from java.util import ArrayList
          |print(ArrayList)
          |""".stripMargin

      // again, we first demonstrate a failure when the DelegatingFinder is missing
      try {
        assertOutput(removeDelegatingFinder + defDummyFinder + conflictingDummy) { case _ => }
      } catch {
        case e: RuntimeException =>
          e.getMessage shouldEqual "ImportError: java.lang.ClassNotFoundException: java.dummy1datetime.datetime"
      }

      // now let's add DelegatingFinder back
      assertOutput(addDelegatingFinder + defDummyFinder + conflictingDummy) {
        case (vars, output) =>
          vars should have size 2
          val Array(jepModule, datetime, sys, arrayList) = stdOut(output).split("\n")
          jepModule should startWith("<module 'java' (<jep.java_import_hook.JepJavaImporter")
          datetime shouldEqual "<class 'datetime.datetime'>"
          sys shouldEqual "<module 'sys' (built-in)>"
          arrayList shouldEqual "class java.util.ArrayList"
      }
    }
  }
}
