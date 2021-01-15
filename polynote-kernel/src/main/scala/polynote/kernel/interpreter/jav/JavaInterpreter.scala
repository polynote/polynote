package polynote.kernel.interpreter
package jav

import java.io.File

import com.googlecode.totallylazy
import polynote.kernel.environment.{Config, CurrentNotebook, CurrentTask}
import polynote.kernel.interpreter.jav.javarepl.{EvaluationContext, Evaluator, Result}
import polynote.kernel.task.TaskManager
import polynote.kernel.{Completion, InterpreterEnv, ResultValue, ScalaCompiler, Signatures}
import polynote.messages.CellID
import zio.blocking.Blocking
import zio.{RIO, Task, ZIO}

import scala.collection.JavaConverters._

class JavaInterpreter private[jav] (
  val evaluator: Evaluator,
  val scalaCompiler: ScalaCompiler) extends Interpreter {

  case class CellCode()

  case class JavaCellState(id: CellID, prev: State, values: List[ResultValue], cellCode: CellCode, instance: Option[AnyRef]) extends State {
    override def withPrev(prev: State): JavaCellState = copy(prev = prev)
    override def updateValues(fn: ResultValue => ResultValue): State = copy(values = values.map(fn))
    override def updateValuesM[R](fn: ResultValue => RIO[R, ResultValue]): RIO[R, State] =
      ZIO.collectAll(values.map(fn)).map(values => copy(values = values))
  }

  def makeJavaResult(resultValue: ResultValue): Result = {
    Result.result(resultValue.name, resultValue.value, totallylazy.Option.some(classOf[Object]))
  }

  def makeJavaResults(resultValues: List[ResultValue]): java.util.List[Result] = {
    resultValues.map { rv => makeJavaResult(rv) }.asJava
  }

  override def run(code: String, state: State): RIO[InterpreterEnv, State] = {
    val eio = zio.blocking.effectBlocking {
      evaluator.setResults(makeJavaResults(state.scope))
      evaluator.evaluate(code)
    }
    eio.flatMap { e =>
      if(e.isRight) {
        val resultOption = e.right().result()
        if (resultOption.isDefined) {
          val result = resultOption.get()
          val javaType = result.`type`()
          import scalaCompiler.global._
          val scalaType = scalaCompiler.global.rootMirror.getClassByName(TypeName(javaType.getTypeName)).thisType
          val rv = ResultValue(result.key(), javaType.getTypeName, Nil, state.id, result.value(), scalaType, None)
          ZIO.succeed(JavaCellState(state.id, state.prev, List(rv), CellCode(), None))
        } else {
          ZIO.succeed(JavaCellState(state.id, state.prev, List(), CellCode(), None))
        }
      } else {
        ZIO.fail(e.left())
      }
    }
  }

  override def completionsAt(code: String, pos: Int, state: State): RIO[Blocking, List[Completion]] = ZIO.succeed(List())

  override def parametersAt(code: String, pos: Int, state: State): RIO[Blocking, Option[Signatures]] = ZIO.succeed(None)

  override def init(state: State): RIO[InterpreterEnv, State] = ZIO.succeed(state)

  override def shutdown(): Task[Unit] = ZIO.unit // TODO
}

object JavaInterpreter {
  def apply(): RIO[Blocking with Config with ScalaCompiler.Provider with CurrentNotebook with CurrentTask with TaskManager, JavaInterpreter] = {
    for {
      compiler <- ScalaCompiler.access
    } yield {
      val outputDirectory = new File(compiler.outputDir)
      val context = EvaluationContext.evaluationContext(outputDirectory)
      val classLoader = compiler.classLoader
      new JavaInterpreter(new Evaluator(context, classLoader), compiler)
    }
  }

  object Factory extends Interpreter.Factory {
    def languageName: String = "Java"
    def apply(): RIO[Blocking with Config with ScalaCompiler.Provider with CurrentNotebook with CurrentTask with TaskManager, Interpreter] = JavaInterpreter()
  }
}
