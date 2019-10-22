package polynote.testing

import java.io.File

import cats.data.StateT
import cats.syntax.traverse._
import cats.instances.list._
import polynote.config.PolynoteConfig
import polynote.kernel.environment.Config
import polynote.kernel.{Output, Result, ScalaCompiler}
import polynote.kernel.interpreter.{Interpreter, State}
import polynote.kernel.logging.Logging
import polynote.testing.kernel.MockEnv
import zio.{RIO, ZIO}
import zio.blocking.Blocking
import zio.clock.Clock
import zio.console.Console
import zio.random.Random
import zio.system.System
import zio.interop.catz._

import scala.reflect.internal.util.AbstractFileClassLoader
import scala.reflect.io.VirtualDirectory
import scala.tools.nsc.Settings
import scala.tools.nsc.io.AbstractFile

trait InterpreterSpec extends ZIOSpec {

  lazy val classpath: List[File] = sys.props("java.class.path").split(File.pathSeparator).toList.map(new File(_))
  lazy val settings: Settings = ScalaCompiler.defaultSettings(new Settings(), classpath)

  lazy val outDir: AbstractFile = new VirtualDirectory("(memory)", None)
  settings.outputDirs.setSingleOutput(outDir)

  lazy val classLoader: AbstractFileClassLoader = unsafeRun(ScalaCompiler.makeClassLoader(settings).provide(Config.of(PolynoteConfig())))
  lazy val compiler: ScalaCompiler = ScalaCompiler(settings, ZIO.succeed(classLoader)).runIO()

  def interpreter: Interpreter

  lazy val initialState: State = unsafeRun(interpreter.init(State.Root).provideSomeM(MockEnv(State.Root.id + 1)))
  lazy val cellState: State = State.id(1, initialState)

  def assertOutput(code: String)(assertion: (Map[String, Any], Seq[Result]) => Unit): Unit =
    assertOutput(List(code))(assertion)

  def assertOutput(code: Seq[String])(assertion: (Map[String, Any], Seq[Result]) => Unit): Unit= {
    val (finalState, interpResults) = code.toList.map(interp).sequence.run(cellState).runIO()
    val terminalResults = interpResults.foldLeft((Map.empty[String, Any], List.empty[Result])) {
      case ((vars, results), next) =>
        val nextVars = vars ++ next.state.values.map(v => v.name -> v.value).toMap
        val nextOutputs = results ++ next.env.publishResult.toList.runIO()
        (nextVars, nextOutputs)
    }
    assertion.tupled(terminalResults)
  }

  case class InterpResult(state: State, env: MockEnv)

  type ITask[A] = RIO[Clock with Console with System with Random with Blocking with Logging, A]

  def interp(code: String): StateT[ITask, State, InterpResult] = StateT[ITask, State, InterpResult] {
    state => MockEnv(state.id).flatMap {
      env => interpreter.run(code, state).map {
        newState => State.id(newState.id + 1, newState) -> InterpResult(newState, env)
      }.provide(env.toCellEnv(classLoader))
    }
  }

  def interp1(code: String): InterpResult = unsafeRun {
    MockEnv(cellState.id).flatMap {
      env =>
        interpreter.run(code, cellState).provide(env.toCellEnv(getClass.getClassLoader)).map {
          state => InterpResult(state, env)
        }
    }
  }

  def stdOut(results: Seq[Result]): String = results.foldLeft("") {
    case (accum, Output("text/plain; rel=stdout", next)) => accum + next
    case (accum, _) => accum
  }

}
