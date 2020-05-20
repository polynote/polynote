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
import zio.stm.{ TRef => ZTRef }

class TRef[F[+_], A] private (val underlying: ZTRef[A]) extends AnyVal {
  self =>

  /**
   * See `zio.stm.TRef#get`
   */
  final def get: STM[F, A] = new STM(underlying.get)

  /**
   * Switch from effect F to effect G.
   */
  def mapK[G[+_]]: TRef[G, A] = new TRef(underlying)

  /**
   * See `zio.stm.TRef#modify`
   */
  final def modify[B](f: A => (B, A)): STM[F, B] = new STM(underlying.modify(f))

  /**
   * See `zio.stm.TRef#modifySome`
   */
  final def modifySome[B](default: B)(f: PartialFunction[A, (B, A)]): STM[F, B] =
    new STM(underlying.modifySome(default)(f))

  /**
   * See `zio.stm.TRef#set`
   */
  final def set(newValue: A): STM[F, Unit] = new STM(underlying.set(newValue))

  override final def toString = underlying.toString

  /**
   * See `zio.stm.TRef#update`
   */
  final def update(f: A => A): STM[F, A] = new STM(underlying.updateAndGet(f))

  /**
   * See `zio.stm.TRef#updateSome`
   */
  final def updateSome(f: PartialFunction[A, A]): STM[F, A] = new STM(underlying.updateSomeAndGet(f))
}

object TRef {

  final def make[F[+_], A](a: => A): STM[F, TRef[F, A]] =
    new STM(ZTRef.make(a).map(new TRef(_)))

  final def makeCommit[F[+_], A](a: => A)(implicit R: Runtime[Any], A: Async[F]): F[TRef[F, A]] =
    STM.atomically(make(a))
}
