package polynote.kernel.interpreter
package jav

import java.io.File

import polynote.kernel.environment.{Config, CurrentNotebook, CurrentTask}
import polynote.kernel.task.TaskManager
import polynote.kernel.{Completion, CompletionType, InterpreterEnv, ResultValue, ScalaCompiler, Signatures}
import polynote.messages.{CellID, ShortString}
import zio.blocking.Blocking
import zio.{RIO, Task, ZIO}
import java.nio.file.{Files, Path}
import java.util.function.{IntFunction, Predicate}

import polynote.kernel.interpreter.jav.completion.{Completer, TypeResolver}
import polynote.kernel.interpreter.jav.eval.{EvaluationContext, EvaluationResult, Evaluator}
import polynote.kernel.interpreter.jav.expressions.{Expression, Import}

import scala.collection.mutable
import scala.reflect.io.AbstractFile

class JavaInterpreter private[jav] (
  val evaluator: Evaluator,
  val scalaCompiler: ScalaCompiler) extends Interpreter {

  val completer: Completer = completion.defaultCompleter(evaluator, TypeResolver.defaultPackageResolver)

  case class JavaCellState(id: CellID,
                           prev: State,
                           values: List[ResultValue],
                           javaValue: Option[EvaluationResult],
                           importExpression: Option[Expression]) extends State {
    override def withPrev(prev: State): JavaCellState = copy(prev = prev)
    override def updateValues(fn: ResultValue => ResultValue): State = copy(values = values.map(fn))
    override def updateValuesM[R](fn: ResultValue => RIO[R, ResultValue]): RIO[R, State] =
      ZIO.collectAll(values.map(fn)).map(values => copy(values = values))
  }

  def scope(thisState: State): List[EvaluationResult] = {
    def makeJavaResult(resultValue: ResultValue): EvaluationResult = {
      EvaluationResult(resultValue.name, resultValue.value.asInstanceOf[Object], Some(classOf[Object]))
    }

    val visible = new mutable.ListMap[String, EvaluationResult]
    var state = thisState
    while (state != State.Root) {
      state match {
        case jcs: JavaCellState =>
          jcs.javaValue.foreach {
            r =>
              if (!visible.contains(r.key)) {
                visible.put(r.key, r)
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
    visible.values.toList
  }

  def importExpressions(thisState: State): List[Expression] = {
    thisState.collect {
      case jcs: JavaCellState => jcs.importExpression
    }.collect {
      case Some(e) => e
    }
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
          Files.walk(evaluator.outputDirectory.toPath).filter(new Predicate[Path] {
            override def test(t: Path): Boolean = t.getFileName.toString.endsWith(".java") && !t.getFileName.toString.startsWith("Evaluation$")
          }).toArray(new IntFunction[Array[Path]] {
            override def apply(value: Int): Array[Path] = new Array[Path](value)
          })
        }.flatMap {
          javaFiles => ZIO.foreach_(javaFiles.toSeq)(file => scalaCompiler.compileJava(AbstractFile.getFile(file.toFile)))
        }
    }
    
    eio.flatMap {
      case Right(evaluation) => {
        evaluation.result match {
          case Some(result) => {
            val rv = buildResultValue(state, result)

            ZIO.succeed(JavaCellState(state.id, state.prev, List(rv), Some(result), None))
          }
          case None => {
            val importExpression = evaluation.expression match {
              case ie: Import => Some(ie)
              case _ => None
            }
            ZIO.succeed(JavaCellState(state.id, state.prev, List(), None, importExpression))
          }
        }
      }
      case Left(err) => ZIO.fail(err)
    }
  }

  def buildResultValue(state: State, result: EvaluationResult): ResultValue = {
    // TODO figure out a consistent way to go from java Type to scala Type. This allows Scala to use Java values
    // without having to do asInstanceOf[]
    import scalaCompiler.global._

    val javaType = result.resolveType
    val rm = scalaCompiler.global.rootMirror
    val (scalaType, typeAsString) = javaType match {
      case java.lang.Boolean.TYPE => (rm.typeOf[Boolean], "boolean")
      case JavaEvalTypes.BOOLEAN_ARRAY_TYPE => (rm.typeOf[Array[Boolean]], "boolean[]")
      case java.lang.Byte.TYPE => (rm.typeOf[Byte], "byte")
      case JavaEvalTypes.BYTE_ARRAY_TYPE => (rm.typeOf[Array[Byte]], "byte[]")
      case java.lang.Character.TYPE => (rm.typeOf[Char], "char")
      case JavaEvalTypes.CHAR_ARRAY_TYPE => (rm.typeOf[Array[Char]], "char[]")
      case java.lang.Short.TYPE => (rm.typeOf[Short], "short")
      case JavaEvalTypes.SHORT_ARRAY_TYPE => (rm.typeOf[Array[Short]], "short[]")
      case java.lang.Integer.TYPE => (rm.typeOf[Int], "int")
      case JavaEvalTypes.INT_ARRAY_TYPE => (rm.typeOf[Array[Int]], "int[]")
      case java.lang.Long.TYPE => (rm.typeOf[Long], "long")
      case JavaEvalTypes.LONG_ARRAY_TYPE => (rm.typeOf[Array[Long]], "long[]")
      case java.lang.Float.TYPE => (rm.typeOf[Float], "float")
      case JavaEvalTypes.FLOAT_ARRAY_TYPE => (rm.typeOf[Array[Float]], "float[]")
      case java.lang.Double.TYPE => (rm.typeOf[Double], "double")
      case JavaEvalTypes.DOUBLE_ARRAY_TYPE => (rm.typeOf[Array[Double]], "double[]")
      case _ => {
        try {
          // TODO figure out how to get this to work for types with params and for type defined in the interpreter
          // This will throw an exception on generic java types, e.g. `List<String>`.
          //   object java.util.List[java.lang.String] in compiler mirror not found.
          // scala.reflect.runtime.JavaMirrors.JavaMirror.typeToScala seems like just what I want
          // val classMirror = scala.reflect.runtime.universe.runtimeMirror(evaluator.classLoader)
          // val scalaType = classMirror.staticClass(hackedTypeName)

          val hackedTypeName = javaType.getTypeName.replace('<', '[').replace('>', ']')

          (rm.getClassByName(TypeName(hackedTypeName)).thisType, javaType.getTypeName)
        } catch {
          case err: Throwable => {
            err.printStackTrace()
            // We know it isn't a primitive type, so just tell Scala it's an AnyRef :(
            (rm.typeOf[AnyRef], "(*) " + javaType.getTypeName)
          }
        }
      }
    }

    ResultValue(result.key, typeAsString, Nil, state.id, result.value, scalaType, None)
  }

  override def completionsAt(code: String, pos: Int, state: State): RIO[Blocking, List[Completion]] = zio.blocking.effectBlocking {
    completer.complete(code.substring(0, pos)).candidates.toList.map { c =>
      Completion(c.value, Nil, Nil, ShortString(""), CompletionType.Unknown)
    }
  }

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
      val context = EvaluationContext(outputDirectory)
      val classLoader = compiler.classLoader
      new JavaInterpreter(new Evaluator(context, classLoader), compiler)
    }
  }

  def forTesting(): RIO[ScalaCompiler.Provider, JavaInterpreter] = {
    for {
      compiler <- ScalaCompiler.access
    } yield {
      val outputDirectory = new File(compiler.outputDir)
      val context = EvaluationContext(outputDirectory)
      val classLoader = compiler.classLoader
      new JavaInterpreter(new Evaluator(context, classLoader), compiler)
    }
  }

  object Factory extends Interpreter.Factory {
    def languageName: String = "Java"
    def apply(): RIO[Blocking with Config with ScalaCompiler.Provider with CurrentNotebook with CurrentTask with TaskManager, Interpreter] = JavaInterpreter()
  }
}
