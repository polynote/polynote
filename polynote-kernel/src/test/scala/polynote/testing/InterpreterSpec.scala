package polynote.testing

import cats.{FlatMap, MonadError, StackSafeMonad}

import java.io.File
import cats.data.StateT
import cats.syntax.traverse._
import cats.instances.list._
import org.scalatest.Suite
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

import scala.reflect.internal.util.AbstractFileClassLoader
import scala.reflect.io.VirtualDirectory
import scala.tools.nsc.Settings
import scala.tools.nsc.io.AbstractFile

trait InterpreterSpec extends ConfiguredZIOSpec { this: Suite =>
  import runtime.{unsafeRun, unsafeRunSync}
  val classpath: List[File] = sys.props("java.class.path").split(File.pathSeparator).toList.map(new File(_))
  val settings: Settings = ScalaCompiler.defaultSettings(new Settings(), classpath)

  def outDir: AbstractFile = new VirtualDirectory("(memory)", None)
  settings.outputDirs.setSingleOutput(outDir)

  val classLoader: AbstractFileClassLoader = unsafeRun(ScalaCompiler.makeClassLoader(settings, Nil).provide(Config.of(PolynoteConfig())))
  val compiler: ScalaCompiler = ScalaCompiler(settings, classLoader).runIO()

  protected implicit def zioMonad[R, E]: MonadError[ZIO[R, E, *], E] = new MonadError[ZIO[R, E, *], E] with StackSafeMonad[ZIO[R, E, *]] {
    override final def pure[A](a: A): ZIO[R, E, A]                                         = ZIO.succeed(a)
    override final def map[A, B](fa: ZIO[R, E, A])(f: A => B): ZIO[R, E, B]                = fa.map(f)
    override final def flatMap[A, B](fa: ZIO[R, E, A])(f: A => ZIO[R, E, B]): ZIO[R, E, B] = fa.flatMap(f)
    override final def flatTap[A, B](fa: ZIO[R, E, A])(f: A => ZIO[R, E, B]): ZIO[R, E, A] = fa.tap(f)

    override final def widen[A, B >: A](fa: ZIO[R, E, A]): ZIO[R, E, B]                                = fa
    override final def map2[A, B, Z](fa: ZIO[R, E, A], fb: ZIO[R, E, B])(f: (A, B) => Z): ZIO[R, E, Z] = fa.zipWith(fb)(f)
    override final def as[A, B](fa: ZIO[R, E, A], b: B): ZIO[R, E, B]                                  = fa.as(b)
    override final def whenA[A](cond: Boolean)(f: => ZIO[R, E, A]): ZIO[R, E, Unit]                    = ZIO.effectSuspendTotal(f).when(cond)
    override final def unit: ZIO[R, E, Unit]                                                           = ZIO.unit

    override final def handleErrorWith[A](fa: ZIO[R, E, A])(f: E => ZIO[R, E, A]): ZIO[R, E, A] = fa.catchAll(f)
    override final def recoverWith[A](fa: ZIO[R, E, A])(pf: PartialFunction[E, ZIO[R, E, A]]): ZIO[R, E, A] =
      fa.catchSome(pf)
    override final def raiseError[A](e: E): ZIO[R, E, A] = ZIO.fail(e)

    override final def attempt[A](fa: ZIO[R, E, A]): ZIO[R, E, Either[E, A]] = fa.either
  }

  def interpreter: Interpreter

  lazy val initialState: State = interpreter.init(State.Root).provideSomeLayer[Environment](MockEnv.layer(State.Root.id + 1)).runIO()
  def cellState: State = State.id(1, initialState)

  def assertOutput(code: String)(assertion: (Map[String, Any], Seq[Result]) => Unit): Unit =
    assertOutput(List(code))(assertion)

  def assertOutput(code: Seq[String])(assertion: (Map[String, Any], Seq[Result]) => Unit): Unit = {
    val (finalState, interpResults) = code.toList.map(c => interp(c)).sequence.run(cellState).runIO()
    assertResults(finalState, interpResults, assertion)
  }

  def assertPolyOutput(code: List[(Interpreter, String)])(assertion: (Map[String, Any], Seq[Result]) => Unit): Unit = {
    val (finalState, interpResults) =
      code
        .map {
          case (interpreter, codeStr) =>
            interp(codeStr, interpreter)
        }
        .sequence
        .run(cellState)
        .runIO()
   assertResults(finalState, interpResults, assertion)
  }

  private def assertResults(finalState: State, results: List[InterpResult], assertion: (Map[String, Any], Seq[Result]) => Unit): Unit = {
    val terminalResults = results.foldLeft((Map.empty[String, Any], List.empty[Result])) {
      case ((vars, results), next) =>
        val nextVars = vars ++ next.state.values.map(v => v.name -> v.value).toMap
        val nextOutputs = results ++ next.env.publishResult.toList.runIO()
        (nextVars, nextOutputs)
    }
    assertion.tupled(terminalResults)
  }

  case class InterpResult(state: State, env: MockEnv)

  type ITask[A] = RIO[Environment, A]

  def interp(code: String, interpreter: Interpreter = interpreter): StateT[ITask, State, InterpResult] = StateT[ITask, State, InterpResult] {
    state => MockEnv(state.id).flatMap {
      env => interpreter.run(code, state).map {
        newState => State.id(newState.id + 1, newState) -> InterpResult(newState, env)
      }.provideSomeLayer[Environment](env.toCellEnv(classLoader))
    }
  }

  def interp1(code: String): InterpResult = unsafeRun {
    MockEnv(cellState.id).flatMap {
      env =>
        interpreter.run(code, cellState).provideSomeLayer(env.toCellEnv(getClass.getClassLoader)).map {
          state => InterpResult(state, env)
        }
    }
  }

  def stdOut(results: Seq[Result]): String = results.foldLeft("") {
    case (accum, Output("text/plain; rel=stdout", next)) => accum + next.mkString
    case (accum, _) => accum
  }

  def stdErr(results: Seq[Result]): String = results.foldLeft("") {
    case (accum, Output("text/plain; rel=stderr", next)) => accum + next.mkString
    case (accum, _) => accum
  }

}
