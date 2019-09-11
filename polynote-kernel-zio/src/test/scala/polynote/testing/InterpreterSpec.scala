package polynote.testing

import cats.data.StateT
import cats.syntax.traverse._
import cats.instances.list._
import polynote.kernel.{Result, ScalaCompiler}
import polynote.kernel.interpreter.{Interpreter, MockEnv, State}
import zio.{TaskR, ZIO}
import zio.blocking.Blocking
import zio.clock.Clock
import zio.console.Console
import zio.random.Random
import zio.system.System
import zio.interop.catz._

import scala.reflect.internal.util.AbstractFileClassLoader
import scala.reflect.io.VirtualDirectory
import scala.tools.nsc.Settings

trait InterpreterSpec extends ZIOSpec {

  val settings: Settings = ScalaCompiler.defaultSettings(new Settings())
  val outDir: VirtualDirectory = new VirtualDirectory("(memory)", None)
  settings.outputDirs.setSingleOutput(outDir)

  val classLoader: AbstractFileClassLoader = new AbstractFileClassLoader(outDir, getClass.getClassLoader)
  val compiler: ScalaCompiler = ScalaCompiler(settings, ZIO.succeed(classLoader)).runIO()

  def interpreter: Interpreter

  def assertOutput(code: String)(assertion: (Map[String, Any], Seq[Result]) => Unit): Unit =
    assertOutput(List(code))(assertion)

  def assertOutput(code: Seq[String])(assertion: (Map[String, Any], Seq[Result]) => Unit): Unit= {
    val (finalState, interpResults) = code.toList.map(interp).sequence.run(State.id(1)).runIO()
    val terminalResults = interpResults.foldLeft((Map.empty[String, Any], List.empty[Result])) {
      case ((vars, results), next) =>
        val nextVars = vars ++ next.state.values.map(v => v.name -> v.value).toMap
        val nextOutputs = results ++ next.env.publishResult.toList.runIO()
        (nextVars, nextOutputs)
    }
    assertion.tupled(terminalResults)
  }

  case class InterpResult(state: State, env: MockEnv)
  type ITask[A] = TaskR[Clock with Console with System with Random with Blocking, A]
  def interp(code: String): StateT[ITask, State, InterpResult] = StateT[ITask, State, InterpResult] {
    state => MockEnv(state.id).flatMap {
      env => interpreter.run(code, state).map {
        newState => State.id(newState.id + 1, newState) -> InterpResult(newState, env)
      }.provide(env.toCellEnv(classLoader))
    }
  }

}
