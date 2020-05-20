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

package zio
package interop

import cats.~>
import cats.arrow.FunctionK
import cats.effect.Resource.{ Allocate, Bind, Suspend }
import cats.effect.{ Async, Effect, ExitCase, LiftIO, Resource, Sync, IO => CIO }
import cats.{ Bifunctor, Monad, MonadError, Monoid, Semigroup, SemigroupK }
import cats.arrow.ArrowChoice
import zio.ZManaged.ReleaseMap

trait CatsZManagedSyntax {
  import scala.language.implicitConversions

  implicit final def catsIOResourceSyntax[F[_], A](resource: Resource[F, A]): CatsIOResourceSyntax[F, A] =
    new CatsIOResourceSyntax(resource)

  implicit final def zioResourceSyntax[R, E <: Throwable, A](r: Resource[ZIO[R, E, ?], A]): ZIOResourceSyntax[R, E, A] =
    new ZIOResourceSyntax(r)


}

final class ZIOResourceSyntax[R, E <: Throwable, A](private val resource: Resource[ZIO[R, E, ?], A]) extends AnyVal {

  /**
   * Convert a cats Resource into a ZManaged.
   * Beware that unhandled error during release of the resource will result in the fiber dying.
   */
  def toManagedZIO: ZManaged[R, E, A] = {
    def go[A1](res: Resource[ZIO[R, E, ?], A1]): ZManaged[R, E, A1] =
      res match {
        case alloc: Allocate[ZIO[R, E, ?], A1] =>
          ZManaged.makeReserve(alloc.resource.map {
            case (a, r) => Reservation(ZIO.succeedNow(a), e => r(exitToExitCase(e)).orDie)
          })

        case bind: Bind[ZIO[R, E, ?], a, A1] =>
          go(bind.source).flatMap(s => go(bind.fs(s)))

        case suspend: Suspend[ZIO[R, E, ?], A1] =>
          ZManaged.unwrap(suspend.resource.map(go))
      }

    go(resource)
  }
}

final class CatsIOResourceSyntax[F[_], A](private val resource: Resource[F, A]) extends AnyVal {

  /**
   * Convert a cats Resource into a ZManaged.
   * Beware that unhandled error during release of the resource will result in the fiber dying.
   */
  def toManaged[R](implicit L: LiftIO[ZIO[R, Throwable, ?]], F: Effect[F]): ZManaged[R, Throwable, A] = {
    import catz.core._

    new ZIOResourceSyntax(
      resource.mapK(Lambda[F ~> ZIO[R, Throwable, ?]](L liftIO F.toIO(_)))
    ).toManagedZIO
  }
}

trait CatsEffectZManagedInstances {

  implicit def liftIOZManagedInstances[R](
    implicit ev: LiftIO[ZIO[R, Throwable, ?]]
  ): LiftIO[ZManaged[R, Throwable, ?]] =
    new LiftIO[ZManaged[R, Throwable, ?]] {
      override def liftIO[A](ioa: CIO[A]): ZManaged[R, Throwable, A] =
        ZManaged.fromEffect(ev.liftIO(ioa))
    }

  implicit def syncZManagedInstances[R]: Sync[ZManaged[R, Throwable, ?]] =
    new CatsZManagedSync[R]

}

trait CatsZManagedInstances extends CatsZManagedInstances2 {

  implicit def monadErrorZManagedInstances[R, E]: MonadError[ZManaged[R, E, ?], E] =
    new CatsZManagedMonadError

  implicit def monoidZManagedInstances[R, E, A](implicit ev: Monoid[A]): Monoid[ZManaged[R, E, A]] =
    new Monoid[ZManaged[R, E, A]] {
      override def empty: ZManaged[R, E, A] = ZManaged.succeedNow(ev.empty)

      override def combine(x: ZManaged[R, E, A], y: ZManaged[R, E, A]): ZManaged[R, E, A] = x.zipWith(y)(ev.combine)
    }

  implicit def arrowChoiceRManagedInstances[E]: ArrowChoice[RManaged] =
    CatsZManagedArrowChoice.asInstanceOf[ArrowChoice[RManaged]]
}

sealed trait CatsZManagedInstances2 {
  implicit def arrowChoiceZManagedInstances[E]: ArrowChoice[ZManaged[*, E, *]] =
    CatsZManagedArrowChoice.asInstanceOf[ArrowChoice[ZManaged[*, E, *]]]
}

private class CatsZManagedMonad[R, E] extends Monad[ZManaged[R, E, ?]] {
  override def pure[A](x: A): ZManaged[R, E, A] = ZManaged.succeedNow(x)

  override def flatMap[A, B](fa: ZManaged[R, E, A])(f: A => ZManaged[R, E, B]): ZManaged[R, E, B] = fa.flatMap(f)

  override def tailRecM[A, B](a: A)(f: A => ZManaged[R, E, Either[A, B]]): ZManaged[R, E, B] =
    ZManaged.suspend(f(a)).flatMap {
      case Left(nextA) => tailRecM(nextA)(f)
      case Right(b)    => ZManaged.succeedNow(b)
    }
}

private class CatsZManagedMonadError[R, E] extends CatsZManagedMonad[R, E] with MonadError[ZManaged[R, E, ?], E] {
  override def raiseError[A](e: E): ZManaged[R, E, A] = ZManaged.fromEffect(ZIO.fail(e))

  override def handleErrorWith[A](fa: ZManaged[R, E, A])(f: E => ZManaged[R, E, A]): ZManaged[R, E, A] =
    fa.catchAll(f)
}

/**
 * lossy, throws away errors using the "first success" interpretation of SemigroupK
 */
private class CatsZManagedSemigroupK[R, E] extends SemigroupK[ZManaged[R, E, ?]] {
  override def combineK[A](x: ZManaged[R, E, A], y: ZManaged[R, E, A]): ZManaged[R, E, A] =
    x.orElse(y)
}

private class CatsZManagedSync[R] extends CatsZManagedMonadError[R, Throwable] with Sync[ZManaged[R, Throwable, ?]] {

  override final def delay[A](thunk: => A): ZManaged[R, Throwable, A] = ZManaged.fromEffect(ZIO.effect(thunk))

  override final def suspend[A](thunk: => ZManaged[R, Throwable, A]): ZManaged[R, Throwable, A] =
    ZManaged.unwrap(ZIO.effect(thunk))

  override def bracketCase[A, B](
    acquire: ZManaged[R, Throwable, A]
  )(use: A => ZManaged[R, Throwable, B])(
    release: (A, cats.effect.ExitCase[Throwable]) => ZManaged[R, Throwable, Unit]
  ): ZManaged[R, Throwable, B] =
    ZManaged {
      ZIO.uninterruptibleMask { restore =>
        (for {
          a     <- acquire
          exitB <- ZManaged(restore(use(a).zio)).run
          _     <- release(a, exitToExitCase(exitB))
          b     <- ZManaged.done(exitB)
        } yield b).zio
      }
    }
}

private object CatsZManagedArrowChoice extends ArrowChoice[ZManaged[*, Any, *]] {
  final override def lift[A, B](f: A => B): ZManaged[A, Any, B] = ZManaged.fromFunction(f)
  final override def compose[A, B, C](f: ZManaged[B, Any, C], g: ZManaged[A, Any, B]): ZManaged[A, Any, C] =
    f compose g

  final override def id[A]: ZManaged[A, Any, A] = ZManaged.identity
  final override def dimap[A, B, C, D](fab: ZManaged[A, Any, B])(f: C => A)(g: B => D): ZManaged[C, Any, D] =
    fab.provideSome(f).map(g)

  final override def choose[A, B, C, D](f: ZManaged[A, Any, C])(
    g: ZManaged[B, Any, D]
  ): ZManaged[Either[A, B], Any, Either[C, D]] = f +++ g

  final override def first[A, B, C](fa: ZManaged[A, Any, B]): ZManaged[(A, C), Any, (B, C)]  = fa *** ZManaged.identity
  final override def second[A, B, C](fa: ZManaged[A, Any, B]): ZManaged[(C, A), Any, (C, B)] = ZManaged.identity *** fa
  final override def split[A, B, C, D](f: ZManaged[A, Any, B], g: ZManaged[C, Any, D]): ZManaged[(A, C), Any, (B, D)] =
    f *** g

  final override def merge[A, B, C](f: ZManaged[A, Any, B], g: ZManaged[A, Any, C]): ZManaged[A, Any, (B, C)] = f.zip(g)
  final override def lmap[A, B, C](fab: ZManaged[A, Any, B])(f: C => A): ZManaged[C, Any, B]                  = fab.provideSome(f)
  final override def rmap[A, B, C](fab: ZManaged[A, Any, B])(f: B => C): ZManaged[A, Any, C]                  = fab.map(f)
  final override def choice[A, B, C](f: ZManaged[A, Any, C], g: ZManaged[B, Any, C]): ZManaged[Either[A, B], Any, C] =
    f ||| g
}
