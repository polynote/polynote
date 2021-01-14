package polynote.kernel.interpreter
package jav

import java.io.File

import polynote.kernel.interpreter.jav.javarepl.{EvaluationClassLoader, EvaluationContext, Evaluator}
import polynote.kernel.{Completion, InterpreterEnv, ResultValue, ScalaCompiler, Signatures}
import polynote.kernel.environment.{Config, CurrentNotebook, CurrentTask}
import polynote.kernel.task.TaskManager
import polynote.messages.CellID
import zio.{RIO, Task, ZIO}
import zio.blocking.Blocking

/**
  * @author dray
  */
class JavaInterpreter private[jav] (
  val evaluator: Evaluator) extends Interpreter {

  case class CellCode()

  /**
    * A [[State]] implementation for Java cells. It tracks the CellCode and the instance of the cell class, which
    * we'll need to pass into future cells if they use types, classes, etc from this cell.
    */
  case class JavaCellState(id: CellID, prev: State, values: List[ResultValue], cellCode: CellCode, instance: Option[AnyRef]) extends State {
    override def withPrev(prev: State): JavaCellState = copy(prev = prev)
    override def updateValues(fn: ResultValue => ResultValue): State = copy(values = values.map(fn))
    override def updateValuesM[R](fn: ResultValue => RIO[R, ResultValue]): RIO[R, State] =
      ZIO.collectAll(values.map(fn)).map(values => copy(values = values))
  }

  override def run(code: String, state: State): RIO[InterpreterEnv, State] = zio.blocking.effectBlocking {
    val e = evaluator.evaluate(code)
    if(e.isRight) {
      val resultOption = e.right().result()
      if(resultOption.isDefined) {
        val result = resultOption.get()
        val rv = ResultValue(result.key(), result.`type`().getTypeName, Nil, state.id, result.value(), scala.reflect.runtime.universe.NoType, None)
        JavaCellState(state.id, state.prev, List(rv), CellCode(), None)
      } else {
        JavaCellState(state.id, state.prev, List(), CellCode(), None)
      }
    } else {
      JavaCellState(state.id, state.prev, List(), CellCode(), None)
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
      val outputDirectory = new File(compiler.global.settings.outputDirs.getSingleOutput.get.absolute.canonicalPath)
      val context = EvaluationContext.evaluationContext(outputDirectory)
      val classLoader = compiler.classLoader // EvaluationClassLoader.evaluationClassLoader(context, compiler.classLoader)
      new JavaInterpreter(new Evaluator(context, classLoader))
    }
  }

  object Factory extends Interpreter.Factory {
    def languageName: String = "Java"
    def apply(): RIO[Blocking with Config with ScalaCompiler.Provider with CurrentNotebook with CurrentTask with TaskManager, Interpreter] = JavaInterpreter()
  }
}
