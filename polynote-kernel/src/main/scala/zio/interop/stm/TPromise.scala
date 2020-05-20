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

import zio.stm.{ TPromise => ZTPromise }

/**
 * See [[zio.stm.TPromise]]
 */
class TPromise[F[+_], E <: Throwable, A] private (underlying: ZTPromise[E, A]) {

  /**
   * See [[zio.stm.TPromise#await]]
   */
  final def await: STM[F, A] = new STM(underlying.await)

  /**
   * See [[zio.stm.TPromise#done]]
   */
  final def done(v: Either[E, A]): STM[F, Boolean] = new STM(underlying.done(v))

  /**
   * See [[zio.stm.TPromise#fail]]
   */
  final def fail(e: E): STM[F, Boolean] =
    done(Left(e))

  /**
   * Switch from effect F to effect G.
   */
  def mapK[G[+_]]: TPromise[G, E, A] = new TPromise(underlying)

  /**
   * See [[zio.stm.TPromise#poll]]
   */
  final def poll: STM[F, Option[STM[F, A]]] = new STM(underlying.poll.map(_.map(new STM(_))))

  /**
   * See [[zio.stm.TPromise#succeed]]
   */
  final def succeed(a: A): STM[F, Boolean] =
    done(Right(a))
}

object TPromise {
  final def make[F[+_], E <: Throwable, A]: STM[F, TPromise[F, E, A]] =
    new STM(ZTPromise.make[E, A].map(new TPromise(_)))
}
