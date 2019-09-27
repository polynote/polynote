package polynote

import cats.effect.concurrent.Ref
import fs2.Stream
import polynote.config.PolynoteConfig
import polynote.kernel.environment.{Config, CurrentNotebook, CurrentRuntime, CurrentTask, PublishResult, PublishStatus}
import polynote.kernel.interpreter.Interpreter
import polynote.kernel.logging.Logging
import polynote.messages.Notebook
import zio.{Task, TaskR, ZIO}
import zio.blocking.Blocking
import zio.clock.Clock
import zio.system.System

package object kernel {

  type BaseEnv = Blocking with Clock with System with Logging
  trait BaseEnvT extends Blocking with Clock with System with Logging

  // some type aliases jut to avoid env clutter
  type TaskB[+A] = TaskR[BaseEnv, A]
  type TaskC[+A] = TaskR[BaseEnv with GlobalEnv with CellEnv, A]
  type TaskG[+A] = TaskR[BaseEnv with GlobalEnv, A]

  type GlobalEnv = Config with Interpreter.Factories with Kernel.Factory
  trait GlobalEnvT extends Config with Interpreter.Factories with Kernel.Factory
  def GlobalEnv(config: PolynoteConfig, interpFactories: Map[String, List[Interpreter.Factory]], kernelFactoryService: Kernel.Factory.Service): GlobalEnv = new GlobalEnvT {
    val polynoteConfig: PolynoteConfig = config
    val interpreterFactories: Map[String, List[Interpreter.Factory]] = interpFactories
    val kernelFactory: Kernel.Factory.Service = kernelFactoryService
  }


  // CellEnv is provided to the kernel by its host when a cell is being run
  type CellEnv = CurrentNotebook with TaskManager with PublishStatus with PublishResult
  trait CellEnvT extends CurrentNotebook with TaskManager with PublishStatus with PublishResult

  // InterpreterEnv is provided to the interpreter by the Kernel when running cells
  type InterpreterEnv = Blocking with PublishResult with PublishStatus with CurrentTask with CurrentRuntime
  trait InterpreterEnvT extends Blocking with PublishResult with PublishStatus with CurrentTask with CurrentRuntime

  implicit class StreamOps[R, A](val stream: Stream[TaskR[R, ?], A]) {

    /**
      * Convenience method to terminate (rather than interrupt) a stream after a given predicate is met. In contrast to
      * [[Stream.interruptWhen]], this allows the stream to finish processing all elements up to and including the
      * element that satisfied the predicate, whereas interruptWhen ungracefully terminates it at once.
      */
    def terminateAfter(fn: A => Boolean): Stream[TaskR[R, ?], A] = stream.flatMap {
      case end if fn(end) => Stream.emits(List(Some(end), None))
      case notEnd         => Stream.emit(Some(notEnd))
    }.unNoneTerminate

  }

  // some tuple syntax that ZIO doesn't natively have
  // PR for including this in ZIO: https://github.com/zio/zio/pull/1444
  final implicit class ZIOTuple4[E, RA, A, RB, B, RC, C, RD, D](
    val zios4: (ZIO[RA, E, A], ZIO[RB, E, B], ZIO[RC, E, C], ZIO[RD, E, D])
  ) extends AnyVal {
    def map4[F](f: (A, B, C, D) => F): ZIO[RA with RB with RC with RD, E, F] =
      for {
        a <- zios4._1
        b <- zios4._2
        c <- zios4._3
        d <- zios4._4
      } yield f(a, b, c, d)
  }

  final implicit class ZIOTuple3[E, RA, A, RB, B, RC, C](
    val zios3: (ZIO[RA, E, A], ZIO[RB, E, B], ZIO[RC, E, C])
  ) extends AnyVal {
    def map3[F](f: (A, B, C) => F): ZIO[RA with RB with RC, E, F] =
      for {
        a <- zios3._1
        b <- zios3._2
        c <- zios3._3
      } yield f(a, b, c)
  }

  /**
    * Filter syntax for ZIO[R, Unit, A] – basically it's OptionT
    */
  final implicit class ZIOOptionSyntax[R, A](val self: ZIO[R, Unit, A]) extends AnyVal {
    def withFilter(predicate: A => Boolean): ZIO[R, Unit, A] = self.filterOrFail(predicate)(())
  }

  // TODO: flesh this out and use it instead of that ^^ so we don't have to throw away errors for optionality
  final case class OptionT[-R, +E, +A](run: ZIO[R, Either[E, Unit], Option[A]]) extends AnyVal {
    def filter(predicate: A => Boolean): OptionT[R, E, A] = copy(run.filterOrFail(_.exists(predicate))(Right(())))
    def map[B](fn: A => B): OptionT[R, E, B] = copy(run.map(_.map(fn)))
    def flatMap[R1 <: R, E1 >: E, B](fn: A => OptionT[R1, E1, B]): OptionT[R1, E1, B] = {
      val next = run.flatMap {
        case None => ZIO.fail(Right(()))
        case Some(a) => fn(a).run
      }
      copy(next)
    }
  }

  /**
    * Some additional syntax for ZIO
    */
  final implicit class ZIOSyntax[R, E, A](val self: ZIO[R, E, A]) extends AnyVal {
    def widen[A1 >: A]: ZIO[R, E, A1] = self
  }

  def withContextClassLoaderIO[A](cl: ClassLoader)(thunk: => A): TaskR[Blocking, A] =
    zio.blocking.effectBlocking(withContextClassLoader(cl)(thunk))

  def withContextClassLoader[A](cl: ClassLoader)(thunk: => A): A = {
    val thread = Thread.currentThread()
    val prevCL = thread.getContextClassLoader
    thread.setContextClassLoader(cl)
    try thunk finally {
      thread.setContextClassLoader(prevCL)
    }
  }


}
