package polynote.kernel.interpreter.jav

import java.io.File

import polynote.kernel.interpreter.jav.completion.{CompletionResult, TypeResolver}
import polynote.kernel.interpreter.jav.eval.{EvaluationClassLoader, EvaluationContext, Evaluator}

/**
  * @author dray
  */
object EvalTest {
  def main(args: Array[String]): Unit = {
    val context = EvaluationContext(new File("/Users/dray/tmp/polynote-java"))
    val evaluator = new Evaluator(
      context,
      new EvaluationClassLoader(context, classOf[Evaluator].getClassLoader))
    //println(evaluator.evaluate("int x = 99"))
    //println(evaluator.evaluate("System.out.println(\"HI! \" + x)"))
    println(evaluator.evaluate("class Foo {}"))
    println(evaluator.evaluate("Foo foooooo = new Foo() { public String toString() { return \"HI MOM\"; }}"))
    println(evaluator.evaluate("System.out.println(foooooo)"))
    //println()

    val completer = completion.defaultCompleter(evaluator, TypeResolver.defaultPackageResolver)
    //printCompletions(completer.complete("Fo"))
    //printCompletions(completer.complete("foo"))
    printCompletions(completer.complete("foooooo."))
    printCompletions(completer.complete("java.io.File."))
    //printCompletions(completer.complete("foooooo.toS"))
    println()
  }

  def printCompletions(result: CompletionResult): Unit = {
    println(s"${result.expression}: ${result.position}")
    result.candidates.foreach { c =>
      println(s"  ${c.value}, ${c.forms}")
    }
    println()
  }
}
