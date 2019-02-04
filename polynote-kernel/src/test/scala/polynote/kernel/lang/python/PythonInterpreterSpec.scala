package polynote.kernel.lang.python

import java.io.{BufferedReader, InputStreamReader, PrintWriter}
import java.util.concurrent.Executors

import cats.effect.internals.IOContextShift
import cats.effect.{ContextShift, IO}
import cats.syntax.apply._
import fs2.concurrent.{Queue, Topic}
import jep.python.{PyCallable, PyObject}
import org.scalatest._
import polynote.kernel.util.RuntimeSymbolTable
import polynote.kernel.{KernelStatusUpdate, Result, UpdatedTasks}

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.tools.nsc.Settings
import scala.tools.nsc.interactive.Global
import scala.tools.nsc.reporters.ConsoleReporter


class PythonInterpreterSpec extends FlatSpec with Matchers {
  val settings = new Settings()
  settings.classpath.append(System.getProperty("java.class.path"))
  settings.YpresentationAnyThread.value = true
  implicit val contextShift: ContextShift[IO] = IOContextShift(ExecutionContext.fromExecutorService(Executors.newCachedThreadPool()))

  "The python kernel" should "be able to display html using the kernel runtime reference" in {
    assertOutput("kernel.display.html('hi')") { (vars, outputs) =>
      val kernel = vars("kernel")
      kernel shouldEqual polynote.runtime.Runtime
      val (mime, html) = outputs.head
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
    assertOutput(code) { (vars, outputs) =>
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

      outputs shouldBe empty
    }
  }

  it should "assign a value to result of python code if it ends in an expression" in {
    val code =
      """
        |x = 1
        |y = 2
        |x + y
      """.stripMargin
    assertOutput(code) { (vars, outputs) =>
      vars("x") shouldEqual 1
      vars("y") shouldEqual 2
      vars("restest") shouldEqual 3

      outputs shouldEqual mutable.ArrayBuffer("text/plain" -> "restest = 3")
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
    assertOutput(code) { (vars, outputs) =>
      vars("x") shouldEqual 1
      vars("y") shouldEqual 2
      vars("answer") shouldEqual 3

      outputs shouldEqual mutable.ArrayBuffer("text/plain" -> "1 + 2 = 3")
    }

  }

  it should "not bother to return any value if the python code just prints" in {
    val code =
      """
        |print("Do you like muffins?")
      """.stripMargin
    assertOutput(code) { (vars, outputs) =>
      vars should have size 1
      vars("kernel") shouldEqual polynote.runtime.Runtime

      outputs shouldEqual mutable.ArrayBuffer("text/plain" -> "Do you like muffins?")
    }
  }

  // TODO: we should consider refactoring the interpreter to improve testability so we don't need all this setup here.
  def assertOutput(code: String)(assertion: (Map[String, Any], Seq[(String, String)]) => Unit): Unit = {
    Topic[IO, KernelStatusUpdate](UpdatedTasks(Nil)).flatMap { updates =>
      val symbolTable = new RuntimeSymbolTable(new Global(settings, new ConsoleReporter(settings, new BufferedReader(new InputStreamReader(System.in)), new PrintWriter(System.out))), getClass.getClassLoader, updates)
      val interp = new PythonInterpreter(symbolTable)
      val displayed = mutable.ArrayBuffer.empty[(String, String)]
      polynote.runtime.Runtime.setDisplayer((mimeType, input) => displayed.append((mimeType, input)))

      Queue.unbounded[IO, Result].flatMap {
        out =>
          Topic[IO, KernelStatusUpdate](UpdatedTasks(Nil)).flatMap {
            statusUpdates =>
              symbolTable.subscribe()(_ => IO.unit).map(_._1).interruptWhen {
                (interp.runCode("test", Nil, Nil, code, out, statusUpdates) *> symbolTable.drain()).attempt
              }.compile.toList.map {
                vars =>
                  val namedVars = vars.map(v => v.name.toString -> v.value).toMap

                  assertion(namedVars, displayed)
              }
          }
      }
      symbolTable.close()
      interp.shutdown()
    }.unsafeRunSync()
  }
}
