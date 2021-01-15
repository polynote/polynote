package polynote.kernel.interpreter
package jav

import java.io.File
import com.googlecode.totallylazy
import polynote.kernel.environment.{Config, CurrentNotebook, CurrentTask}
import polynote.kernel.interpreter.jav.javarepl.expressions.{Expression, Import}
import polynote.kernel.interpreter.jav.javarepl.{EvaluationContext, Evaluator, Result}
import polynote.kernel.task.TaskManager
import polynote.kernel.{Completion, InterpreterEnv, ResultValue, ScalaCompiler, Signatures}
import polynote.messages.CellID
import zio.blocking.Blocking
import zio.{RIO, Task, ZIO}

import java.nio.file.{Files, Path}
import java.util.function.{IntFunction, Predicate}
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.reflect.api.Universe
import scala.reflect.io.AbstractFile

class JavaInterpreter private[jav] (
  val evaluator: Evaluator,
  val scalaCompiler: ScalaCompiler) extends Interpreter {

  case class JavaCellState(id: CellID, prev: State, values: List[ResultValue], javaValue: Option[Result], importExpression: Option[Expression]) extends State {
    override def withPrev(prev: State): JavaCellState = copy(prev = prev)
    override def updateValues(fn: ResultValue => ResultValue): State = copy(values = values.map(fn))
    override def updateValuesM[R](fn: ResultValue => RIO[R, ResultValue]): RIO[R, State] =
      ZIO.collectAll(values.map(fn)).map(values => copy(values = values))
  }

  def scope(thisState: State): java.util.List[Result] = {
    def makeJavaResult(resultValue: ResultValue): Result = {
      Result.result(resultValue.name, resultValue.value, totallylazy.Option.some(classOf[Object]))
    }

    val visible = new mutable.ListMap[String, Result]
    var state = thisState
    while (state != State.Root) {
      state match {
        case jcs: JavaCellState =>
          jcs.javaValue.foreach {
            r =>
              if (!visible.contains(r.key())) {
                visible.put(r.key(), r)
              }
          }
        case _ =>
          state.values.foreach {
            rv =>
              if (!visible.contains(rv.name)) {
                visible.put(rv.name, makeJavaResult(rv))
              }
          }
      }
      state = state.prev
    }
    visible.values.toList.asJava
  }

  def importExpressions(thisState: State): java.util.List[Expression] = {
    thisState.collect {
      case jcs: JavaCellState => jcs.importExpression
    }.collect {
      case Some(e) => e
    }.asJava
  }

  override def run(code: String, state: State): RIO[InterpreterEnv, State] = {
    val eio = zio.blocking.effectBlocking {
      evaluator.setResults(scope(state))
      evaluator.setExpressions(importExpressions(state))
      evaluator.evaluate(code)
    }.tap {
      _ =>
        // hacky – listing all the java files just to see if this will work
        zio.blocking.effectBlocking {
          Files.walk(evaluator.outputDirectory().toPath).filter(new Predicate[Path] {
            override def test(t: Path): Boolean = t.getFileName.toString.endsWith(".java")
          }).toArray(new IntFunction[Array[Path]] {
            override def apply(value: Int): Array[Path] = new Array[Path](value)
          })
        }.flatMap {
          javaFiles => ZIO.foreach_(javaFiles.toSeq)(file => scalaCompiler.compileJava(AbstractFile.getFile(file.toFile)))
        }
    }
    
    eio.flatMap { e =>
      if(e.isRight) {
        val evaluation = e.right()
        val resultOption = evaluation.result()
        if (resultOption.isDefined) {
          val result = resultOption.get()
          val javaType = result.`type`()
          // TODO figure out a consistent way to go from java Type to scala Type
          val rv = scala.util.Try {
            import scalaCompiler.global._
            val scalaType = scalaCompiler.global.rootMirror.getClassByName(TypeName(javaType.getTypeName)).thisType
            ResultValue(result.key(), javaType.getTypeName, Nil, state.id, result.value(), scalaType, None)
          } getOrElse {
            val scalaType = scala.reflect.runtime.universe.NoType
            ResultValue(result.key(), javaType.getTypeName, Nil, state.id, result.value(), scalaType, None)
          }

          ZIO.succeed(JavaCellState(state.id, state.prev, List(rv), Some(result), None))
        } else {
          val importExpression = evaluation.expression() match {
            case ie: Import => Some(ie)
            case _ => None
          }
          ZIO.succeed(JavaCellState(state.id, state.prev, List(), None, importExpression))
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
