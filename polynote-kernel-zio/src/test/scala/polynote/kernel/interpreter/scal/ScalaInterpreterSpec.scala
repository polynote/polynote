package polynote.kernel.interpreter
package scal

import cats.data.StateT
import cats.syntax.traverse._
import cats.instances.list._
import org.scalatest.{FreeSpec, Matchers}
import polynote.kernel.{Output, Result, ResultValue, ScalaCompiler, TaskInfo}
import polynote.testing.{InterpreterSpec, ValueMap, ZIOSpec}
import polynote.messages.CellID
import zio.{TaskR, ZIO}
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

class ScalaInterpreterSpec extends FreeSpec with Matchers with InterpreterSpec {

  val interpreter: ScalaInterpreter = ScalaInterpreter().provide(new ScalaCompiler.Provider {
    val scalaCompiler: ScalaCompiler = compiler
  }).runIO()
  import interpreter.ScalaCellState


  "run scala code" in {
    val result = interp1("val foo = 22")
    ValueMap(result.state.values) shouldEqual Map("foo" -> 22)
  }

  "capture standard output" in {
    val result = interp1("""println("hello")""")

    stdOut(result.env.publishResult.toList.runIO()) shouldEqual "hello\n"
  }

  "bring values from previous cells" in {
    val test = for {
      res1 <- interp("val foo = 22")
      res2 <- interp("val bar = foo + 10")
    } yield (res1, res2)

    val (finalState, (res1, res2)) = test.run(cellState).runIO()

    res2.state.values match {
      case ValueMap(values) => values("bar") shouldEqual 32
    }
  }

  "minimizes dependencies" in {
    val test = for {
      res1 <- interp("val foo = 22\nval wizzle = true")
      res2 <- interp("val bar = foo + 10")
    } yield (res1, res2)

    val (finalState, (res1, res2)) = test.run(cellState).runIO()

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

      val (finalState, (res1, res2)) = test.run(cellState).runIO()
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

      val (finalState, (res1, res2, res3)) = test.run(cellState).runIO()

      val hooey = res3.state.values.head
      hooey.name shouldEqual "hooey"
      hooey.typeName shouldEqual "Int"
      hooey.value shouldEqual 22
    }
  }

  /**
    * This test takes a while, so it's disabled by default. The purpose is to make sure that the cell encoding
    * doesn't fail at the typer stage before the constructor arguments are pruned, because there's at least one
    * constructor argument for each previous cell at that point.
    *
    * The time overhead does grow as the cell count grows, but in this test the cell i=256 takes only 0.1 seconds to run
    * (on my laptop - JS) compared to 0.01 seconds for cell i=1, so it's not a hugely burdensome thing. The entire test
    * takes about 1m45s (around half a second for each cell on average), so there is some other overhead going on, but
    * it doesn't seem to be in the Scala interpreter itself.
    */
  "doesn't fail after really running 256 cells" ignore {
    val (finalState, results) = (0 to 256).toList.map {
      i => interp(s"val foo$i = $i").transformF {
        task => for {
          _      <- ZIO.effectTotal(println(s"Starting cell $i"))
          start  <- zio.clock.nanoTime
          result <- task
          end    <- zio.clock.nanoTime
          _      <- ZIO.effectTotal(println(s"Cell $i took ${(end - start).toDouble / 10e9} seconds"))
        } yield result
      }
    }.sequence.run(State.id(0)).runIO()
  }

  /**
    * A quick version of the above; doesn't actually *run* 256 cells, just creates a state chain to put 256 result values
    * into scope and runs one cell in that state
    */
  "doesn't fail after 256 cells" in {
    val prevState = (0 to 256).foldLeft(State.root) {
      (prev, i) => State.id(i, prev, List(ResultValue(s"foo$i", "Int", Nil, CellID(i), i, compiler.global.typeOf[Int], None)))
    }

    val (finalState, results) = interp("val foo257 = 257").run(State.id(257, prevState)).runIO()
    ValueMap(results.state.values) shouldEqual Map("foo257" -> 257)
    ValueMap(results.state.scope) shouldEqual (0 to 257).map(i => s"foo$i" -> i).toMap
  }

  "cases from previous scala interpreter" - {
    "be able to display html using the kernel runtime reference" in {
      val code = """kernel.display.html("hi")"""
      assertOutput(code) {
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
      assertOutput(code) {
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
      assertOutput(code) {
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
      assertOutput(code) {
        (vars, output) =>
          vars.toSeq should contain theSameElementsAs Seq(
            "x" -> 1,
            "y" -> 2,
            "answer" -> 3,
            "Out" -> 3
          )
          stdOut(output) shouldEqual
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
      assertOutput(code) {
        (vars, output) =>
          vars shouldBe empty
          stdOut(output) shouldEqual "Do you like muffins?\n"
      }
    }

    "support destructured assignment" in {
      val code =
        """
          |val (foo, bar) = 1 -> "one"
      """.stripMargin

      assertOutput(code) {
        (vars, output) =>
          vars.toSeq should contain theSameElementsAs List("foo" -> 1, "bar" -> "one")
      }
    }

  }



}
