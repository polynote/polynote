package polynote

import fs2.Stream
import polynote.config.PolynoteConfig
import polynote.kernel.interpreter.Interpreter
import zio.{Task, TaskR, ZIO}
import zio.blocking.Blocking
import zio.clock.Clock
import zio.system.System

package object kernel {

  type BaseEnv = Blocking with Clock with System
  trait BaseEnvT extends Blocking with Clock with System

  type KernelConfigEnv = BaseEnv with ScalaCompiler.Provider with Interpreter.Factories with CurrentNotebook with PolynoteConfig.Provider
  trait KernelConfigEnvT extends BaseEnvT with ScalaCompiler.Provider with Interpreter.Factories with CurrentNotebook with PolynoteConfig.Provider

  type KernelFactoryEnv = BaseEnv with TaskManager.Provider[KernelEnv] with ScalaCompiler.Provider with Interpreter.Factories with CurrentNotebook with PolynoteConfig.Provider
  trait KernelFactoryEnvT extends BaseEnvT with TaskManager.Provider[KernelEnv] with ScalaCompiler.Provider with Interpreter.Factories with CurrentNotebook with PolynoteConfig.Provider

  type KernelEnv = BaseEnv with PublishStatus with PublishResults
  trait KernelEnvT extends BaseEnvT with PublishStatus with PublishResults

  type KernelTaskEnv = KernelEnv with CurrentTask
  trait KernelTaskEnvT extends KernelEnvT with CurrentTask

  type CellEnv = KernelEnv with CurrentTask with CurrentRuntime
  trait CellEnvT extends KernelEnvT with CurrentTask with CurrentRuntime

  implicit class StreamOps[A](val stream: Stream[Task, A]) {

    /**
      * Convenience method to terminate (rather than interrupt) a stream after a given predicate is met. In contrast to
      * [[Stream.interruptWhen]], this allows the stream to finish processing all elements up to and including the
      * element that satisfied the predicate, whereas interruptWhen ungracefully terminates it at once.
      */
    def terminateAfter(fn: A => Boolean): Stream[Task, A] = stream.flatMap {
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

  def withContextClassLoaderIO[A](cl: ClassLoader)(thunk: => A): TaskR[Blocking, A] =
    zio.blocking.effectBlocking(withContextClassLoader(cl)(thunk))

  def withContextClassLoader[A](cl: ClassLoader)(thunk: => A): A = {
    val thread = Thread.currentThread()
    val prevCL = thread.getContextClassLoader
    try thunk finally {
      thread.setContextClassLoader(prevCL)
    }
  }


}
