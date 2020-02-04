package polynote

import cats.effect.Concurrent
import cats.effect.concurrent.Ref
import fs2.Stream
import polynote.config.PolynoteConfig
import polynote.kernel.environment.{Config, CurrentNotebook, CurrentRuntime, CurrentTask, PublishResult, PublishStatus}
import polynote.kernel.interpreter.Interpreter
import polynote.kernel.logging.Logging
import polynote.messages.Notebook
import zio.{Promise, RIO, Task, UIO, ZIO}
import zio.blocking.Blocking
import zio.clock.Clock
import zio.system.System

package object kernel {

  type BaseEnv = Blocking with Clock with System with Logging
  trait BaseEnvT extends Blocking with Clock with System with Logging

  // some type aliases jut to avoid env clutter
  type TaskB[+A] = RIO[BaseEnv, A]
  type TaskC[+A] = RIO[BaseEnv with GlobalEnv with CellEnv, A]
  type TaskG[+A] = RIO[BaseEnv with GlobalEnv, A]

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

  final implicit class StreamThrowableOps[R, A](val stream: Stream[RIO[R, ?], A]) {

    /**
      * Convenience method to terminate (rather than interrupt) a stream after a given predicate is met. In contrast to
      * [[Stream.interruptWhen]], this allows the stream to finish processing all elements up to and including the
      * element that satisfied the predicate, whereas interruptWhen ungracefully terminates it at once.
      */
    def terminateAfter(fn: A => Boolean): Stream[RIO[R, ?], A] = stream.flatMap {
      case end if fn(end) => Stream.emits(List(Some(end), None))
      case notEnd         => Stream.emit(Some(notEnd))
    }.unNoneTerminate

    def terminateAfterEquals(value: A): Stream[RIO[R, ?], A] = terminateAfter(_ == value)

    def interruptAndIgnoreWhen(signal: Promise[Throwable, Unit])(implicit concurrent: Concurrent[RIO[R, ?]]): Stream[RIO[R, ?], A] =
      stream.interruptWhen(signal.await.either.as(Right(()): Either[Throwable, Unit]))

  }

  final implicit class StreamUIOps[A](val stream: Stream[UIO, A]) {

    def interruptAndIgnoreWhen(signal: Promise[Throwable, Unit])(implicit concurrent: Concurrent[UIO]): Stream[UIO, A] =
      stream.interruptWhen(signal.await.either.as(Right(()): Either[Throwable, Unit]))
  }

  /**
    * Filter syntax for ZIO[R, Unit, A] – basically it's OptionT
    */
  final implicit class ZIOOptionSyntax[R, A](val self: ZIO[R, Unit, A]) extends AnyVal {
    def withFilter(predicate: A => Boolean): ZIO[R, Unit, A] = self.filterOrFail(predicate)(())
  }

  /**
    * Some additional syntax for ZIO
    */
  final implicit class ZIOSyntax[R, E, A](val self: ZIO[R, E, A]) extends AnyVal {
    def widen[A1 >: A]: ZIO[R, E, A1] = self
  }

  final implicit class RIOSyntax[R, A](val self: ZIO[R, Throwable, A]) extends AnyVal {
    def withFilter(predicate: A => Boolean): ZIO[R, Throwable, A] = self.filterOrFail(predicate)(new MatchError("Predicate is not satisfied"))
  }

  def withContextClassLoaderIO[A](cl: ClassLoader)(thunk: => A): RIO[Blocking, A] =
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
