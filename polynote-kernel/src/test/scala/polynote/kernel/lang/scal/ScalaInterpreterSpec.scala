package polynote.kernel.lang.scal

import org.scalatest._
import polynote.kernel.Output
import polynote.kernel.lang.KernelSpec

class ScalaInterpreterSpec extends FlatSpec with Matchers with KernelSpec {

  "The scala kernel" should "be able to display html using the kernel runtime reference" in {
    val code = """kernel.display.html("hi")"""
    assertScalaOutput(code) { case (vars, output, displayed) =>
      vars.toSeq should contain only "kernel" -> polynote.runtime.Runtime

      output shouldBe empty
      displayed should contain only "text/html" -> "hi"
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
      vars.toSeq.filterNot(_._1 == "z") should contain theSameElementsAs Seq(
        "kernel" -> polynote.runtime.Runtime,
        "x" -> 1,
        "y" -> "foo",
        "l" -> List(1, "foo", Map("sup?" -> "nm"), false),
        "l2" -> List(100, List(1, "foo", Map("sup?" -> "nm"), false)),
        "m" -> Map(1 -> "foo", "foo" -> 100, "hey!" ->  List(100, List(1, "foo", Map("sup?" -> "nm"), false))),
        "m2" -> Map(
          "hm" -> Map(1 -> "foo", "foo" -> 100, "hey!" ->  List(100, List(1, "foo", Map("sup?" -> "nm"), false))),
          "humm" -> Map(1 -> "foo", "foo" -> 100, "hey!" ->  List(100, List(1, "foo", Map("sup?" -> "nm"), false))))
      )
      vars("z").toString should (include("$notebook.Eval$test$") and include("$MyNewClass"))


      displayed shouldBe empty
      output shouldBe empty
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
      vars.toSeq should contain theSameElementsAs Seq(
        "kernel" -> polynote.runtime.Runtime,
        "x" -> 1,
        "y" -> 2,
        "restest" -> 3
      )

      displayed shouldBe empty
      output should contain only Output("text/plain; rel=decl; lang=scala", "restest: Int = 3")
    }
  }

  it should "capture all output of the code" in {
    val code =
      """
        |val x = 1
        |val y = 2
        |println(s"println: $x + $y = ${x + y}")
        |System.out.println(s"sys: $x + $y = ${x + y}")
        |val answer = x + y
        |answer
      """.stripMargin
    assertScalaOutput(code) { case (vars, output, displayed) =>
      vars.toSeq should contain theSameElementsAs Seq(
        "kernel" -> polynote.runtime.Runtime,
        "x" -> 1,
        "y" -> 2,
        "answer" -> 3,
        "restest" -> 3
      )

      displayed shouldBe empty
      output should contain theSameElementsAs Seq(
        Output("text/plain; rel=stdout", "println: 1 + 2 = 3"),
        Output("text/plain; rel=stdout", "sys: 1 + 2 = 3"),
        Output("text/plain; rel=decl; lang=scala", "restest: Int = 3")
      )
    }

  }

  it should "not bother to return any value if the code just prints" in {
    val code =
      """
        |println("Do you like muffins?")
      """.stripMargin
    assertScalaOutput(code) { (vars, output, displayed) =>
      vars.toSeq should contain only "kernel" -> polynote.runtime.Runtime

      displayed shouldBe empty
      output should contain only Output("text/plain; rel=stdout", "Do you like muffins?")
    }
  }
}
