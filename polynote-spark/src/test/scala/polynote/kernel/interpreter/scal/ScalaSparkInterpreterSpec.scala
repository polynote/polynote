package polynote.kernel.interpreter
package scal

import org.apache.spark.sql.SparkSession
import org.scalatest.{FreeSpec, Matchers}
import polynote.kernel.ScalaCompiler
import polynote.kernel.environment.Env
import polynote.testing.InterpreterSpec
import zio.blocking.Blocking

import scala.reflect.io.{PlainDirectory, VirtualDirectory}
import scala.tools.nsc.Settings
import scala.tools.nsc.io.{AbstractFile, Directory}

class ScalaSparkInterpreterSpec extends FreeSpec with InterpreterSpec with Matchers {
  override lazy val outDir: AbstractFile = new PlainDirectory(new Directory(org.apache.spark.repl.Main.outputDir))

  val spark: SparkSession = {
    import org.apache.spark.repl.Main
    Main.conf.setAppName("Polynote tests")
    Main.conf.setMaster("local[*]")
    Main.conf.set("spark.driver.host", "127.0.0.1")
    Main.createSparkSession()
  }

  lazy val interpreter: ScalaInterpreter = ScalaSparkInterpreter().provideSomeM(Env.enrich[Blocking](ScalaCompiler.Provider.of(compiler))).runIO()


  "The Scala Spark Kernel" - {
    "properly run simple spark jobs" in {
      val code =
        """
          |val x = 1
          |spark.sparkContext.parallelize(Seq(1,2,3)).map(_ + x).collect.toList
        """.stripMargin
      assertOutput(code) {
        case (vars, output) =>
          vars("x") shouldEqual 1
          vars("Out") shouldEqual List(2, 3, 4)
      }
    }

    "not be affected by unused unserializable values" in {
      val code = Seq(
        "def banana: Int = 1",
        "val foo = banana",
        "implicit val i: Int = 10",
        """
          |val x = 100
          |val y = new {}
          |val z = {
          |  val foo = 1000
          |  foo
          |}
          |val implicitResult = implicitly[Int]
        """.stripMargin,
        "spark.sparkContext.parallelize(Seq(1,2,3)).map(_ + x).collect.toList"
      )
      assertOutput(code) {
        case (vars, output) =>
          vars("foo") shouldEqual 1
          vars("i") shouldEqual 10
          vars("x") shouldEqual 100
          vars("z") shouldEqual 1000
          vars("implicitResult") shouldEqual 10
          vars("Out") shouldEqual List(101, 102, 103)
      }
    }

    "allow values to be overridden" in {
      val code = Seq(
        "val a: Int = 100",
        "val a: Int = 200",
        "val b = a"
      )
      assertOutput(code) {
        case (vars, output) =>
          vars("a") shouldEqual 200
          vars("b") shouldEqual 200
      }
    }

    "work with implicits" in {
      val code = Seq(
        "implicit val a: Int = 100",
        "implicit val a: Int = 200", // check implicit override
        """
          |def foo(a: Int)(implicit s: Int) = a + s
          |val b = foo(1)
        """.stripMargin)
      assertOutput(code) {
        case (vars, output) =>
          vars("a") shouldEqual 200
          vars("b") shouldEqual 201
      }
    }

    "work with class defs" in {
      val code = Seq(
        """
          |case class Foo(i: Int)
          |val fooInstance = Foo(1)
          |""".stripMargin,
        """class Bar(j: Int) {
          |  def i: Int = j
          |}
        """.stripMargin,
        "abstract class Baz(val i: Int)",
        "class BazImpl(i: Int) extends Baz(i)",
        """trait Quux {
          |  def i: Int
          |  def j: Int = 1000
          |}""".stripMargin,
        """class QuuxImpl extends Quux {
          |  override def i: Int = 100
          |}
        """.stripMargin,
        """
          |case class Beep(s: String)
          |object Beep {
          |  def apply(i: Int): Beep = Beep(i.toString)
          |}
          |""".stripMargin,
        "object Bop { val i = Foo(4).i + 1 }",
        """
          |val foo = Foo(0).i + fooInstance.i
          |val bar = new Bar(2).i
          |val baz = new BazImpl(3).i
          |val quux = new QuuxImpl()
          |val quuxI = quux.i
          |val quuxJ = quux.j
          |val beep1 = Beep("four").s
          |val beep2 = Beep(4).s
          |val bop = Bop.i
          |""".stripMargin)
      assertOutput(code) {
        case (vars, output) =>
          vars("foo") shouldEqual 1
          vars("bar") shouldEqual 2
          vars("baz") shouldEqual 3
          vars("quuxI") shouldEqual 100
          vars("quuxJ") shouldEqual 1000
          vars("beep1") shouldEqual "four"
          vars("beep2") shouldEqual "4"
          vars("bop") shouldEqual 5
      }
    }

    "work with lazy vals and vars" in {
      val code = Seq(
        "var a = 100",
        "lazy val b = a"
      )
      assertOutput(code) {
        case (vars, output) =>
          vars("a") shouldEqual 100
          vars("b") shouldEqual 100
      }
    }

    "work with default values" in {
      val code = Seq(
        """case class Foo(a: Int, b: Int, c: Int = 1) { def sum: Int = a + b + c }""".stripMargin,
        """def foo(a: Int, b: Int = 1) = Foo(a, b).sum
          |foo(1)
        """.stripMargin,
        "val a = foo(2, 3)",
        "val b = foo(2)",
        "val c = Foo(2, 3).sum"
      )
      assertOutput(code) {
        case (vars, output) =>
          vars("Out") shouldEqual 3
          vars("a") shouldEqual 6
          vars("b") shouldEqual 4
          vars("c") shouldEqual 6
      }
    }

    "properly lift imports as needed" in {
      val code = Seq(
        """
          |trait SomeTrait {
          |  def foo: Int = 1
          |}
        """.stripMargin,
        """
          |class MyImpl extends SomeTrait {
          |  override def foo: Int = 2
          |}
        """.stripMargin,
        "new MyImpl().foo"
      )
      assertOutput(code) {
        case (vars, output) =>
          vars("Out") shouldEqual 2
      }
    }

    // FAILS!!!!
    "work with inner traits if properly imported" ignore {
      val code = Seq(
        """
          |object HideThing {
          |  trait SomeTrait {
          |    def foo: Int = 1
          |  }
          |}
        """.stripMargin,
        """
          |import HideThing.SomeTrait
          |def foo(t: SomeTrait) = 1""".stripMargin,
        """
          |class MyImpl extends SomeTrait {
          |  override def foo: Int = 2
          |}
        """.stripMargin,
        "new MyImpl().foo"
      )
      assertOutput(code) {
        case (vars, output) =>
          vars("Out") shouldEqual 2
      }
    }
  }
}
