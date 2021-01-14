package polynote.kernel.interpreter.jav

import org.scalatest.{FreeSpec, Matchers}
import polynote.kernel.interpreter.State
import polynote.kernel.{Output, ScalaCompiler}
import polynote.testing.InterpreterSpec
import polynote.testing.kernel.MockEnv

import scala.tools.nsc.io.AbstractFile

class JavaInterpreterSpec extends FreeSpec with Matchers with InterpreterSpec {

  override lazy val outDir: AbstractFile = settings.outputDirs.getSingleOutput.get

  val interpreter: JavaInterpreter = JavaInterpreter.forTesting().provide(ScalaCompiler.Provider.of(compiler)).runIO()
  interpreter.init(State.Root).provideSomeLayer(MockEnv.init).runIO()

  "JavaInterpreter" - {
    "should assign a value" in {
      val code =
        """
          x = 99 * 100
        """.stripMargin
      assertOutput(code) {
        case (vars, output) =>
          vars("x") shouldEqual 9900
          output shouldBe empty
      }
    }

    "should assign an array value" in {
      assertOutput(Seq(
        "final long[] y = {1, 2, 3, 4};"
      )) {
        case (vars, output) =>
          vars("y") shouldEqual Array(1L, 2L, 3L, 4L)
          output shouldBe empty
      }
    }

    "should assign a generic value" in {
      import scala.collection.JavaConverters._
      assertOutput(Seq(
        "import java.util.List",
        "import java.util.ArrayList",
        "final List<String> y = new ArrayList<String>()",
        "y.add(\"beelzebub\")"
      )) {

        case (vars, output) =>
          vars("y") shouldEqual List("beelzebub").asJava
          output shouldBe empty
      }
    }

    "should make previously assigned values available to subsequent code" in {
      assertOutput(Seq(
        "int x = 99 * 100",
        "String y = \"hello\" + x"
      )) {
        case (vars, output) =>
          vars("x") shouldEqual 9900
          vars("y") shouldEqual "hello9900"
          output shouldBe empty
      }
    }

    "should capture stdout" in {
      assertOutput(Seq(
        "System.out.println(\"hello,world\")"
      )) {
        case (vars, output) =>
          output shouldEqual List(Output("text/plain; rel=stdout", Vector("hello,world\n")))
      }
    }

    "should define classes" in {
      assertOutput(Seq(
        """
          | public class Foo {
          |   public Foo() {}
          |   public String toString() { return "HELLO"; }
          | }
        """.stripMargin,
        "Foo f = new Foo()",
        "System.out.println(\"foo=\" + f)"
      )) {
        case (vars, output) =>
          vars("f").toString shouldEqual "HELLO"
          output shouldEqual List(Output("text/plain; rel=stdout", Vector("foo=HELLO\n")))
      }
    }

  }
}
