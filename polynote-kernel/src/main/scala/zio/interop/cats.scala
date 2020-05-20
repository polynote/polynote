/*
 * Copyright 2017-2019 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.interop

import cats.arrow.ArrowChoice
import cats.effect.{ Concurrent, ContextShift, ExitCase }
import cats.{ effect, _ }
import zio._
import zio.clock.Clock

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{ FiniteDuration, NANOSECONDS, TimeUnit }

object catz extends CatsEffectPlatform {
  object core extends CatsPlatform
  object mtl  extends CatsMtlPlatform
}

abstract class CatsEffectPlatform
    extends CatsEffectInstances
    with CatsEffectZManagedInstances
    with CatsZManagedInstances
    with CatsZManagedSyntax
    with CatsConcurrentEffectSyntax {

  val console: interop.console.cats.type = interop.console.cats

  trait CatsApp extends App {
    implicit val runtime: Runtime[ZEnv] = this
  }

  object implicits {
    implicit final def ioTimer[E]: effect.Timer[IO[E, *]] = ioTimer0.asInstanceOf[effect.Timer[IO[E, *]]]

    private[this] implicit val ioTimer0: effect.Timer[IO[Any, *]] =
      new effect.Timer[IO[Any, *]] {
        override final def clock: effect.Clock[IO[Any, *]] = new effect.Clock[IO[Any, *]] {
          override final def monotonic(unit: TimeUnit): IO[Any, Long] =
            zio.clock.nanoTime.map(unit.convert(_, NANOSECONDS)).provideLayer(ZEnv.live)

          override final def realTime(unit: TimeUnit): IO[Any, Long] =
            zio.clock.currentTime(unit).provideLayer(ZEnv.live)
        }

        override final def sleep(duration: FiniteDuration): IO[Any, Unit] =
          zio.clock.sleep(zio.duration.Duration.fromNanos(duration.toNanos)).provideLayer(ZEnv.live)
      }
  }

}

abstract class CatsPlatform extends CatsInstances with CatsZManagedInstances

abstract class CatsEffectInstances extends CatsInstances with CatsEffectInstances1 {

  implicit final def zioContextShift[R, E]: ContextShift[ZIO[R, E, *]] =
    zioContextShift0.asInstanceOf[ContextShift[ZIO[R, E, *]]]

  implicit final def zioTimer[R <: Clock, E]: effect.Timer[ZIO[R, E, *]] =
    zioTimer0.asInstanceOf[effect.Timer[ZIO[R, E, *]]]

  implicit final def taskEffectInstance[R](implicit runtime: Runtime[R]): effect.ConcurrentEffect[RIO[R, *]] =
    new CatsConcurrentEffect[R](runtime)

  private[this] final val zioContextShift0: ContextShift[ZIO[Any, Any, *]] =
    new ContextShift[ZIO[Any, Any, *]] {
      override final def shift: ZIO[Any, Any, Unit]                                              = ZIO.yieldNow
      override final def evalOn[A](ec: ExecutionContext)(fa: ZIO[Any, Any, A]): ZIO[Any, Any, A] = fa.on(ec)
    }

  private[this] final val zioTimer0: effect.Timer[ZIO[Clock, Any, *]] = new effect.Timer[ZIO[Clock, Any, *]] {
    override final def clock: effect.Clock[ZIO[Clock, Any, *]] = zioCatsClock0
    override final def sleep(duration: FiniteDuration): ZIO[Clock, Any, Unit] =
      zio.clock.sleep(zio.duration.Duration.fromNanos(duration.toNanos))
  }

  private[this] final val zioCatsClock0: effect.Clock[ZIO[Clock, Any, *]] = new effect.Clock[ZIO[Clock, Any, *]] {
    override final def monotonic(unit: TimeUnit): ZIO[Clock, Any, Long] =
      zio.clock.nanoTime.map(unit.convert(_, NANOSECONDS))
    override final def realTime(unit: TimeUnit): ZIO[Clock, Any, Long] =
      zio.clock.currentTime(unit)
  }

}

sealed trait CatsEffectInstances1 {
  implicit final def taskConcurrentInstance[R]: effect.Concurrent[RIO[R, *]] =
    taskConcurrentInstance0.asInstanceOf[effect.Concurrent[RIO[R, *]]]

  private[this] final val taskConcurrentInstance0: effect.Concurrent[RIO[Any, *]] = new CatsConcurrent[Any]
}

abstract class CatsInstances extends CatsInstances1 {

  implicit final def monoidKInstance[R, E: Monoid]: MonoidK[ZIO[R, E, *]] =
    new CatsMonoidK[R, E]

  implicit final def deferInstance[R, E]: Defer[ZIO[R, E, *]] =
    new CatsDefer[R, E]

  implicit final def bifunctorInstance[R]: Bifunctor[ZIO[R, *, *]] =
    bifunctorInstance0.asInstanceOf[Bifunctor[ZIO[R, *, *]]]

  implicit final def rioArrowInstance: ArrowChoice[RIO] =
    zioArrowInstance0.asInstanceOf[ArrowChoice[RIO]]

  implicit final def contravariantInstance[E, A]: Contravariant[ZIO[*, E, A]] =
    contravariantInstance0.asInstanceOf[Contravariant[ZIO[*, E, A]]]

  private[this] val bifunctorInstance0: Bifunctor[ZIO[Any, *, *]]           = new CatsBifunctor
  private[this] val zioArrowInstance0: ArrowChoice[ZIO[*, Any, *]]          = new CatsArrow
  private[this] val contravariantInstance0: Contravariant[ZIO[*, Any, Any]] = new CatsContravariant
}

sealed abstract class CatsInstances1 extends CatsInstances2 {

  implicit final def urioArrowInstance: ArrowChoice[URIO] =
    zioArrowInstance0.asInstanceOf[ArrowChoice[URIO]]

  implicit final def parallelInstance[R, E]: Parallel.Aux[ZIO[R, E, *], ParIO[R, E, *]] =
    parallelInstance0.asInstanceOf[Parallel.Aux[ZIO[R, E, *], ParIO[R, E, *]]]

  implicit final def commutativeApplicativeInstance[R, E]: CommutativeApplicative[ParIO[R, E, *]] =
    commutativeApplicativeInstance0.asInstanceOf[CommutativeApplicative[ParIO[R, E, *]]]

  implicit final def semigroupKInstance[R, E: Semigroup]: SemigroupK[ZIO[R, E, *]] =
    new CatsSemigroupK[R, E]

  private[this] final val parallelInstance0: Parallel.Aux[ZIO[Any, Any, *], ParIO[Any, Any, *]] =
    new CatsParallel[Any, Any](monadErrorInstance)

  private[this] final val commutativeApplicativeInstance0: CommutativeApplicative[ParIO[Any, Any, *]] =
    new CatsParApplicative[Any, Any]
}

sealed abstract class CatsInstances2 {
  protected[this] val zioArrowInstance0: ArrowChoice[ZIO[*, Any, *]] = new CatsArrow

  implicit final def zioArrowInstance[E]: ArrowChoice[ZIO[*, E, *]] =
    zioArrowInstance0.asInstanceOf[ArrowChoice[ZIO[*, E, *]]]

  implicit final def monadErrorInstance[R, E]: MonadError[ZIO[R, E, *], E] =
    monadErrorInstance0.asInstanceOf[MonadError[ZIO[R, E, *], E]]

  implicit final def semigroupKLossyInstance[R, E]: SemigroupK[ZIO[R, E, *]] =
    semigroupKLossyInstance0.asInstanceOf[SemigroupK[ZIO[R, E, *]]]

  private[this] final val monadErrorInstance0: MonadError[ZIO[Any, Any, *], Any] =
    new CatsMonadError[Any, Any]

  private[this] final val semigroupKLossyInstance0: SemigroupK[ZIO[Any, Any, *]] =
    new CatsSemigroupKLossy[Any, Any]
}

private class CatsDefer[R, E] extends Defer[ZIO[R, E, ?]] {
  def defer[A](fa: => ZIO[R, E, A]): ZIO[R, E, A] = ZIO.effectSuspendTotal(fa)
}

private class CatsConcurrentEffect[R](rts: Runtime[R])
    extends CatsConcurrent[R]
    with effect.ConcurrentEffect[RIO[R, *]] {

  override final def runAsync[A](fa: RIO[R, A])(
    cb: Either[Throwable, A] => effect.IO[Unit]
  ): effect.SyncIO[Unit] =
    effect.SyncIO {
      rts.unsafeRunAsync(fa.run) { exit =>
        cb(exit.flatMap(identity).toEither).unsafeRunAsync(_ => ())
      }
    }

  override final def runCancelable[A](fa: RIO[R, A])(
    cb: Either[Throwable, A] => effect.IO[Unit]
  ): effect.SyncIO[effect.CancelToken[RIO[R, *]]] =
    effect.SyncIO {
      rts.unsafeRun {
        ZIO.descriptor
          .bracketExit(
            (descriptor, exit: Exit[Throwable, A]) =>
              ZIO.effectTotal {
                exit match {
                  case Exit.Failure(cause) if !cause.interruptors.forall(_ == descriptor.id) => ()
                  case _ =>
                    effect.IO.suspend(cb(exit.toEither)).unsafeRunAsync(_ => ())
                }
              },
            _ => fa
          )
          .interruptible
          .forkWithErrorHandler(_ => ZIO.unit)
          .tap(ZIO.disown)
          .map(_.interrupt.unit)
      }
    }

  override final def toIO[A](fa: RIO[R, A]): effect.IO[A] =
    effect.ConcurrentEffect.toIOFromRunCancelable(fa)(this)
}

private class CatsConcurrent[R] extends CatsMonadError[R, Throwable] with Concurrent[RIO[R, *]] {

  private[this] final def toFiber[A](f: Fiber[Throwable, A]): effect.Fiber[RIO[R, *], A] =
    new effect.Fiber[RIO[R, *], A] {
      override final val cancel: RIO[R, Unit] = f.interrupt.unit
      override final val join: RIO[R, A]      = f.join
    }

  override final def liftIO[A](ioa: effect.IO[A]): RIO[R, A] =
    Concurrent.liftIO(ioa)(this)

  override final def cancelable[A](k: (Either[Throwable, A] => Unit) => effect.CancelToken[RIO[R, *]]): RIO[R, A] =
    ZIO.effectAsyncInterrupt { kk =>
      val token = k(kk apply _.fold(ZIO.fail(_), ZIO.succeedNow))
      Left(token.orDie)
    }

  override final def race[A, B](fa: RIO[R, A], fb: RIO[R, B]): RIO[R, Either[A, B]] =
    fa.map(Left(_)).interruptible raceFirst fb.map(Right(_)).interruptible

  override final def start[A](fa: RIO[R, A]): RIO[R, effect.Fiber[RIO[R, *], A]] =
    fa.interruptible.forkDaemon.map(toFiber)

  override final def racePair[A, B](
    fa: RIO[R, A],
    fb: RIO[R, B]
  ): RIO[R, Either[(A, effect.Fiber[RIO[R, *], B]), (effect.Fiber[RIO[R, *], A], B)]] =
    (fa.interruptible raceWith fb.interruptible)(
      { case (l, f) => l.fold(f.interrupt *> ZIO.halt(_), ZIO.succeedNow).map(lv => Left((lv, toFiber(f)))) },
      { case (r, f) => r.fold(f.interrupt *> ZIO.halt(_), ZIO.succeedNow).map(rv => Right((toFiber(f), rv))) }
    )

  override final def never[A]: RIO[R, A] =
    ZIO.never

  override final def async[A](k: (Either[Throwable, A] => Unit) => Unit): RIO[R, A] =
    ZIO.effectAsync(kk => k(kk apply _.fold(ZIO.fail(_), ZIO.succeedNow)))

  override final def asyncF[A](k: (Either[Throwable, A] => Unit) => RIO[R, Unit]): RIO[R, A] =
    ZIO.effectAsyncM(kk => k(kk apply _.fold(ZIO.fail(_), ZIO.succeedNow)).orDie)

  override final def suspend[A](thunk: => RIO[R, A]): RIO[R, A] =
    ZIO.effectSuspend(thunk)

  override final def delay[A](thunk: => A): RIO[R, A] =
    ZIO.effect(thunk)

  override final def bracket[A, B](acquire: RIO[R, A])(use: A => RIO[R, B])(release: A => RIO[R, Unit]): RIO[R, B] =
    ZIO.bracket(acquire, release(_: A).orDie, use)

  override final def bracketCase[A, B](acquire: RIO[R, A])(use: A => RIO[R, B])(
    release: (A, ExitCase[Throwable]) => RIO[R, Unit]
  ): RIO[R, B] =
    ZIO.bracketExit(acquire, (a: A, exit: Exit[Throwable, B]) => release(a, exitToExitCase(exit)).orDie, use)

  override final def uncancelable[A](fa: RIO[R, A]): RIO[R, A] =
    fa.uninterruptible

  override final def guarantee[A](fa: RIO[R, A])(finalizer: RIO[R, Unit]): RIO[R, A] =
    fa.ensuring(finalizer.orDie)

  override final def continual[A, B](fa: RIO[R, A])(f: Either[Throwable, A] => RIO[R, B]): RIO[R, B] =
    ZIO.uninterruptibleMask(_(fa).either.flatMap(f))
}

private class CatsMonadError[R, E] extends MonadError[ZIO[R, E, *], E] with StackSafeMonad[ZIO[R, E, *]] {
  override final def pure[A](a: A): ZIO[R, E, A]                                         = ZIO.succeedNow(a)
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

/** lossy, throws away errors using the "first success" interpretation of SemigroupK */
private class CatsSemigroupKLossy[R, E] extends SemigroupK[ZIO[R, E, *]] {
  override final def combineK[A](a: ZIO[R, E, A], b: ZIO[R, E, A]): ZIO[R, E, A] =
    a.catchAll { e1 =>
      b.catchAll { _ =>
        ZIO.fail(e1)
      }
    }
}

private class CatsSemigroupK[R, E: Semigroup] extends SemigroupK[ZIO[R, E, *]] {
  override final def combineK[A](a: ZIO[R, E, A], b: ZIO[R, E, A]): ZIO[R, E, A] =
    a.catchAll { e1 =>
      b.catchAll { e2 =>
        ZIO.fail(Semigroup[E].combine(e1, e2))
      }
    }
}

private class CatsMonoidK[R, E: Monoid] extends CatsSemigroupK[R, E] with MonoidK[ZIO[R, E, *]] {
  override final def empty[A]: ZIO[R, E, A] = ZIO.fail(Monoid[E].empty)
}

private class CatsBifunctor[R] extends Bifunctor[ZIO[R, *, *]] {
  override final def bimap[A, B, C, D](fab: ZIO[R, A, B])(f: A => C, g: B => D): ZIO[R, C, D] =
    fab.bimap(f, g)
}

private class CatsParallel[R, E](final override val monad: Monad[ZIO[R, E, *]]) extends Parallel[ZIO[R, E, *]] {

  final override type F[A] = ParIO[R, E, A]

  final override val applicative: Applicative[ParIO[R, E, *]] =
    new CatsParApplicative[R, E]

  final override val sequential: ParIO[R, E, *] ~> ZIO[R, E, *] =
    new (ParIO[R, E, *] ~> ZIO[R, E, *]) {
      def apply[A](fa: ParIO[R, E, A]): ZIO[R, E, A] = Par.unwrap(fa)
    }

  final override val parallel: ZIO[R, E, *] ~> ParIO[R, E, *] =
    new (ZIO[R, E, *] ~> ParIO[R, E, *]) {
      def apply[A](fa: ZIO[R, E, A]): ParIO[R, E, A] = Par(fa)
    }
}

private class CatsParApplicative[R, E] extends CommutativeApplicative[ParIO[R, E, *]] {

  final override def pure[A](x: A): ParIO[R, E, A] =
    Par(ZIO.succeedNow(x))

  final override def map2[A, B, Z](fa: ParIO[R, E, A], fb: ParIO[R, E, B])(f: (A, B) => Z): ParIO[R, E, Z] =
    Par(Par.unwrap(fa).interruptible.zipWithPar(Par.unwrap(fb).interruptible)(f))

  final override def ap[A, B](ff: ParIO[R, E, A => B])(fa: ParIO[R, E, A]): ParIO[R, E, B] =
    Par(Par.unwrap(ff).interruptible.zipWithPar(Par.unwrap(fa).interruptible)(_(_)))

  final override def product[A, B](fa: ParIO[R, E, A], fb: ParIO[R, E, B]): ParIO[R, E, (A, B)] =
    Par(Par.unwrap(fa).interruptible.zipPar(Par.unwrap(fb).interruptible))

  final override def map[A, B](fa: ParIO[R, E, A])(f: A => B): ParIO[R, E, B] =
    Par(Par.unwrap(fa).map(f))

  final override def unit: ParIO[R, E, Unit] =
    Par(ZIO.unit)
}

private class CatsArrow[E] extends ArrowChoice[ZIO[*, E, *]] {
  final override def lift[A, B](f: A => B): ZIO[A, E, B]                              = ZIO.fromFunction(f)
  final override def compose[A, B, C](f: ZIO[B, E, C], g: ZIO[A, E, B]): ZIO[A, E, C] = f compose g
  final override def id[A]: ZIO[A, E, A]                                              = ZIO.identity
  final override def dimap[A, B, C, D](fab: ZIO[A, E, B])(f: C => A)(g: B => D): ZIO[C, E, D] =
    fab.provideSome(f).map(g)

  final override def choose[A, B, C, D](f: ZIO[A, E, C])(g: ZIO[B, E, D]): ZIO[Either[A, B], E, Either[C, D]] = f +++ g

  final override def first[A, B, C](fa: ZIO[A, E, B]): ZIO[(A, C), E, (B, C)]                    = fa *** ZIO.identity
  final override def second[A, B, C](fa: ZIO[A, E, B]): ZIO[(C, A), E, (C, B)]                   = ZIO.identity *** fa
  final override def split[A, B, C, D](f: ZIO[A, E, B], g: ZIO[C, E, D]): ZIO[(A, C), E, (B, D)] = f *** g
  final override def merge[A, B, C](f: ZIO[A, E, B], g: ZIO[A, E, C]): ZIO[A, E, (B, C)]         = f.zip(g)
  final override def lmap[A, B, C](fab: ZIO[A, E, B])(f: C => A): ZIO[C, E, B]                   = fab.provideSome(f)
  final override def rmap[A, B, C](fab: ZIO[A, E, B])(f: B => C): ZIO[A, E, C]                   = fab.map(f)
  final override def choice[A, B, C](f: ZIO[A, E, C], g: ZIO[B, E, C]): ZIO[Either[A, B], E, C]  = f ||| g
}

final private class CatsContravariant[E, T] extends Contravariant[ZIO[*, E, T]] {
  override def contramap[A, B](fa: ZIO[A, E, T])(f: B => A): ZIO[B, E, T] =
    ZIO.accessM[B](b => fa.provide(f(b)))
}
