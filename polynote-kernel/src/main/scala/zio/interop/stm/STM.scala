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

package zio.interop.stm

import cats.effect.Async
import zio.Runtime
import zio.stm.{ STM => ZSTM }

import scala.util.Try

/**
 * See [[zio.stm.ZSTM]]
 */
final class STM[F[+_], +A] private[stm] (private[stm] val underlying: ZSTM[Throwable, A]) {
  self =>

  /**
   * See `<*>` [[zio.stm.STM]] `<*>`
   */
  final def <*>[B](that: => STM[F, B]): STM[F, (A, B)] =
    self zip that

  /**
   * See [[zio.stm.ZSTM]] `<*`
   */
  final def <*[B](that: => STM[F, B]): STM[F, A] =
    self zipLeft that

  /**
   * See [[zio.stm.ZSTM]] `*>`
   */
  final def *>[B](that: => STM[F, B]): STM[F, B] =
    self zipRight that

  /**
   * See [[zio.stm.ZSTM]] `>>=`
   */
  final def >>=[B](f: A => STM[F, B]): STM[F, B] =
    self flatMap f

  /**
   * See [[zio.stm.ZSTM#collect]]
   */
  final def collect[B](pf: PartialFunction[A, B]): STM[F, B] = new STM(underlying.collect(pf))

  /**
   * See [[zio.stm.ZSTM#commit]]
   */
  final def commit(implicit R: Runtime[Any], A: Async[F]): F[A] = STM.atomically(self)

  final def const[B](b: => B): STM[F, B] = self map (_ => b)

  /**
   * See [[zio.stm.ZSTM#either]]
   */
  final def either: STM[F, Either[Throwable, A]] = new STM(underlying.either)

  /**
   * See [[zio.stm.ZSTM#withFilter]]
   */
  final def filter(f: A => Boolean): STM[F, A] =
    collect {
      case a if f(a) => a
    }

  /**
   * See [[zio.stm.ZSTM#flatMap]]
   */
  final def flatMap[B](f: A => STM[F, B]): STM[F, B] = new STM(underlying.flatMap(f.andThen(_.underlying)))

  final def flatten[B](implicit ev: A <:< STM[F, B]): STM[F, B] =
    self flatMap ev

  /**
   * See [[zio.stm.ZSTM#fold]]
   */
  final def fold[B](f: Throwable => B, g: A => B): STM[F, B] = new STM(underlying.fold(f, g))

  /**
   * See [[zio.stm.ZSTM#foldM]]
   */
  final def foldM[B](f: Throwable => STM[F, B], g: A => STM[F, B]): STM[F, B] =
    new STM(underlying.foldM(f.andThen(_.underlying), g.andThen(_.underlying)))

  /**
   * See [[zio.stm.ZSTM#map]]
   */
  final def map[B](f: A => B): STM[F, B] = new STM(underlying.map(f))

  /**
   * See [[zio.stm.ZSTM#mapError]]
   */
  final def mapError[E1 <: Throwable](f: Throwable => E1): STM[F, A] = new STM(underlying.mapError(f))

  /**
   * Switch from effect F to effect G.
   */
  final def mapK[G[+_]]: STM[G, A] = new STM(underlying)

  /**
   * See [[zio.stm.ZSTM#option]]
   */
  final def option: STM[F, Option[A]] =
    fold[Option[A]](_ => None, Some(_))

  /**
   * See [[zio.stm.ZSTM#orElse]]
   */
  final def orElse[A1 >: A](that: => STM[F, A1]): STM[F, A1] = new STM(underlying.orElse(that.underlying))

  /**
   * See [[zio.stm.ZSTM#orElseEither]]
   */
  final def orElseEither[B](that: => STM[F, B]): STM[F, Either[A, B]] =
    (self map (Left[A, B](_))) orElse (that map (Right[A, B](_)))

  /**
   * See zio.stm.STM.unit
   */
  final def unit: STM[F, Unit] = const(())

  /**
   * See zio.stm.STM.unit
   */
  final def void: STM[F, Unit] = unit

  /**
   * Same as [[filter]]
   */
  final def withFilter(f: A => Boolean): STM[F, A] = filter(f)

  /**
   * See [[zio.stm.ZSTM#zip]]
   */
  final def zip[B](that: => STM[F, B]): STM[F, (A, B)] =
    (self zipWith that)((a, b) => a -> b)

  /**
   * See [[zio.stm.ZSTM#zipLeft]]
   */
  final def zipLeft[B](that: => STM[F, B]): STM[F, A] =
    (self zip that) map (_._1)

  /**
   * See [[zio.stm.ZSTM#zipRight]]
   */
  final def zipRight[B](that: => STM[F, B]): STM[F, B] =
    (self zip that) map (_._2)

  /**
   * See [[zio.stm.ZSTM#zipWith]]
   */
  final def zipWith[B, C](that: => STM[F, B])(f: (A, B) => C): STM[F, C] =
    self flatMap (a => that map (b => f(a, b)))

}

object STM {

  final def atomically[F[+_], A](stm: STM[F, A])(implicit R: Runtime[Any], A: Async[F]): F[A] =
    A.async { cb =>
      R.unsafeRunAsync(ZSTM.atomically(stm.underlying)) { exit =>
        cb(exit.toEither)
      }
    }

  final def check[F[+_]](p: Boolean): STM[F, Unit] =
    if (p) STM.unit else retry

  final def collectAll[F[+_], A](
    i: Iterable[STM[F, A]]
  ): STM[F, List[A]] =
    new STM(ZSTM.collectAll(i.map(_.underlying)))

  final def die[F[+_]](t: Throwable): STM[F, Nothing] =
    succeed(throw t)

  final def dieMessage[F[+_]](m: String): STM[F, Nothing] =
    die(new RuntimeException(m))

  final def fail[F[+_]](e: Throwable): STM[F, Nothing] =
    new STM(ZSTM.fail(e))

  final def foreach[F[+_], A, B](
    as: Iterable[A]
  )(f: A => STM[F, B]): STM[F, List[B]] =
    collectAll(as.map(f))

  final def fromEither[F[+_], A](e: Either[Throwable, A]): STM[F, A] =
    new STM(ZSTM.fromEither(e))

  final def fromTry[F[+_], A](a: => Try[A]): STM[F, A] =
    new STM(ZSTM.fromTry(a))

  final def partial[F[+_], A](a: => A): STM[F, A] =
    fromTry(Try(a))

  final def retry[F[+_]]: STM[F, Nothing] = new STM(ZSTM.retry)

  final def succeed[F[+_], A](a: => A): STM[F, A] = new STM(ZSTM.succeed(a))

  @deprecated("use succeed", "2.0.0.0")
  final def succeedLazy[F[+_], A](a: => A): STM[F, A] =
    new STM(ZSTM.succeed(a))

  final def unit[F[+_]]: STM[F, Unit] = new STM(ZSTM.unit)
}
