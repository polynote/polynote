package polynote.kernel.interpreter
package scal

import cats.data.StateT
import cats.syntax.traverse._
import cats.instances.list._
import org.scalatest.{FreeSpec, Matchers}
import polynote.kernel.{Output, Result, ScalaCompiler, TaskInfo}
import polynote.testing.{ValueMap, ZIOSpec}
import zio.TaskR
import zio.blocking.Blocking
import zio.clock.Clock
import zio.console.Console
import zio.interop.catz._
import zio.random.Random
import zio.system.System

import scala.collection.mutable.ListBuffer
import scala.reflect.internal.util.AbstractFileClassLoader
import scala.reflect.io.VirtualDirectory
import scala.tools.nsc.Settings

class ScalaInterpreterSpec extends FreeSpec with Matchers with ZIOSpec {

  private val settings = ScalaCompiler.defaultSettings(new Settings())
  private val outDir = new VirtualDirectory("(memory)", None)
  settings.outputDirs.setSingleOutput(outDir)

  private val classLoader = new AbstractFileClassLoader(outDir, getClass.getClassLoader)
  private val compiler = ScalaCompiler(settings, "$notebook", classLoader).runIO()
  import compiler.{cellCode, CellCode}

  private val interpreter = ScalaInterpreter().provide(new ScalaCompiler.Provider {
    val scalaCompiler: ScalaCompiler = compiler
  }).runIO()
  import interpreter.ScalaCellState


  "run scala code" in {
    val env = MockEnv(0).runIO()

    val result = interpreter.run(
      "val foo = 22",
      State.id(0)
    ).provide(env.toCellEnv(classLoader)).runIO()

    result match {
      case ScalaCellState(0, State.Root, ValueMap(valueMap), _, _) =>
        valueMap("foo") shouldEqual 22
    }
  }

  "capture standard output" in {
    val env = MockEnv(0).runIO()

    val result = interpreter.run(
      """println("hello")""",
      State.id(0)
    ).provide(env.toCellEnv(classLoader)).runIO()

    env.publishResult.toList.runIO() shouldEqual List(Output("text/plain; rel=stdout", "hello\n"))
  }

  "bring values from previous cells" in {
    val test = for {
      res1 <- interp("val foo = 22")
      res2 <- interp("val bar = foo + 10")
    } yield (res1, res2)

    val (finalState, (res1, res2)) = test.run(State.id(1)).runIO()

    res2.state.values match {
      case ValueMap(values) => values("bar") shouldEqual 32
    }
  }

  "minimizes dependencies" in {
    val test = for {
      res1 <- interp("val foo = 22\nval wizzle = true")
      res2 <- interp("val bar = foo + 10")
    } yield (res1, res2)

    val (finalState, (res1, res2)) = test.run(State.id(1)).runIO()

    res2.state match {
      case state: ScalaCellState =>
        state.cellCode.inputs.count(_.name.toString != "kernel") shouldEqual 1 // foo but not wizzle
        state.cellCode.priorCells.size shouldEqual 0 // doesn't depend on any prior cell instance
    }
  }

  "keep imports from previous cells" - {
    "external imports" in {
      val test = for {
        res1 <- interp(
          """import scala.collection.mutable.ListBuffer
            |val foo = ListBuffer("hi")""".stripMargin)
        res2 <- interp("val bar = ListBuffer(22)")
      } yield (res1, res2)

      val (finalState, (res1, res2)) = test.run(State.id(1)).runIO()
      res1.state.values match {
        case ValueMap(values) => values("foo") shouldEqual ListBuffer("hi")
      }

      res2.state.values match {
        case ValueMap(values) => values("bar") shouldEqual ListBuffer(22)
      }
    }

    "imports of dependent values" in {
      val test = for {
        res1 <- interp("object Foo { val thing = 10 }")
        res2 <- interp("import Foo.thing\nval hey = thing + 2")
        res3 <- interp("val hooey = thing + 12")
      } yield (res1, res2, res3)

      val (finalState, (res1, res2, res3)) = test.run(State.id(1)).runIO()

      val hooey = res3.state.values.head
      hooey.name shouldEqual "hooey"
      hooey.typeName shouldEqual "Int"
      hooey.value shouldEqual 22
    }
  }

  "cases from previous scala interpreter" - {
    "be able to display html using the kernel runtime reference" in {
      val code = """kernel.display.html("hi")"""
      assertScalaOutput(code) {
        (vars, output) =>
          vars.toSeq shouldBe empty
          output should contain only Output("text/html", "hi")
      }
    }

    "properly return vals declared by scala code" in {
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
      assertScalaOutput(code) {
        (vars, output) =>
          vars.toSeq.filterNot(_._1 == "z") should contain theSameElementsAs Seq(
            "x" -> 1,
            "y" -> "foo",
            "l" -> List(1, "foo", Map("sup?" -> "nm"), false),
            "l2" -> List(100, List(1, "foo", Map("sup?" -> "nm"), false)),
            "m" -> Map(1 -> "foo", "foo" -> 100, "hey!" ->  List(100, List(1, "foo", Map("sup?" -> "nm"), false))),
            "m2" -> Map(
              "hm" -> Map(1 -> "foo", "foo" -> 100, "hey!" ->  List(100, List(1, "foo", Map("sup?" -> "nm"), false))),
              "humm" -> Map(1 -> "foo", "foo" -> 100, "hey!" ->  List(100, List(1, "foo", Map("sup?" -> "nm"), false))))
          )
          vars("z").toString should include("$MyNewClass")
          output shouldBe empty
      }
    }

    "assign a value to result of code if it ends in an expression" in {
      val code =
        """
          |val x = 1
          |val y = 2
          |x + y
      """.stripMargin
      assertScalaOutput(code) {
        (vars, output) =>
          vars.toSeq should contain theSameElementsAs Seq(
            "x" -> 1,
            "y" -> 2,
            "Out" -> 3
          )

          output shouldBe empty
      }
    }

    "capture all output of the code" in {
      val code =
        """
          |val x = 1
          |val y = 2
          |println(s"println: $x + $y = ${x + y}")
          |System.out.println(s"sys: $x + $y = ${x + y}")
          |val answer = x + y
          |answer
      """.stripMargin
      assertScalaOutput(code) {
        (vars, output) =>
          vars.toSeq should contain theSameElementsAs Seq(
            "x" -> 1,
            "y" -> 2,
            "answer" -> 3,
            "Out" -> 3
          )
          val outStr = output.foldLeft("") {
            case (accum, Output("text/plain; rel=stdout", next)) => accum + next
            case other => fail(s"Unexpected output $other")
          }
          outStr shouldEqual
            """println: 1 + 2 = 3
              |sys: 1 + 2 = 3
              |""".stripMargin
      }
    }

    "not bother to return any value if the code just prints" in {
      val code =
        """
          |println("Do you like muffins?")
      """.stripMargin
      assertScalaOutput(code) {
        (vars, output) =>
          vars shouldBe empty
          output should contain only Output("text/plain; rel=stdout", "Do you like muffins?\n")
      }
    }

    "support destructured assignment" in {
      val code =
        """
          |val (foo, bar) = 1 -> "one"
      """.stripMargin

      assertScalaOutput(code) {
        (vars, output) =>
          vars.toSeq should contain theSameElementsAs List("foo" -> 1, "bar" -> "one")
      }
    }

  }

  private def assertScalaOutput(code: String)(assertion: (Map[String, Any], Seq[Result]) => Unit): Unit =
    assertScalaOutput(List(code))(assertion)

  private def assertScalaOutput(code: Seq[String])(assertion: (Map[String, Any], Seq[Result]) => Unit): Unit= {
    val (finalState, interpResults) = code.toList.map(interp).sequence.run(State.id(1)).runIO()
    val terminalResults = interpResults.foldLeft((Map.empty[String, Any], List.empty[Result])) {
      case ((vars, results), next) =>
        val nextVars = vars ++ next.state.values.map(v => v.name -> v.value).toMap
        val nextOutputs = results ++ next.env.publishResult.toList.runIO()
        (nextVars, nextOutputs)
    }
    assertion.tupled(terminalResults)
  }

  type ITask[A] = TaskR[Clock with Console with System with Random with Blocking, A]
  private def interp(code: String): StateT[ITask, State, InterpResult] = StateT[ITask, State, InterpResult] {
    state => MockEnv(state.id).flatMap {
      env => interpreter.run(code, state).map {
        newState => State.id(newState.id + 1, newState) -> InterpResult(newState, env)
      }.provide(env.toCellEnv(classLoader))
    }
  }

  private case class InterpResult(state: State, env: MockEnv)

}
