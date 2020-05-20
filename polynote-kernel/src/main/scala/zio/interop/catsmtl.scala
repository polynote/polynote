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

import cats.mtl._
import cats.{ Applicative, Functor }
import zio.ZIO

abstract class CatsMtlPlatform extends CatsMtlInstances

abstract class CatsMtlInstances {

  implicit def zioApplicativeLocalAsk[R, E](implicit ev: Applicative[ZIO[R, E, ?]]): ApplicativeLocal[ZIO[R, E, ?], R] =
    new DefaultApplicativeLocal[ZIO[R, E, ?], R] {
      val applicative: Applicative[ZIO[R, E, ?]]              = ev
      def ask: ZIO[R, Nothing, R]                             = ZIO.environment
      def local[A](f: R => R)(fa: ZIO[R, E, A]): ZIO[R, E, A] = ZIO.accessM(fa provide f(_))
    }

  implicit def zioApplicativeHandle[R, E](implicit ev: Applicative[ZIO[R, E, ?]]): ApplicativeHandle[ZIO[R, E, ?], E] =
    new DefaultApplicativeHandle[ZIO[R, E, ?], E] {
      val functor: Functor[ZIO[R, E, ?]]                                      = ev
      val applicative: Applicative[ZIO[R, E, ?]]                              = ev
      def raise[A](e: E): ZIO[R, E, A]                                        = ZIO.fail(e)
      def handleWith[A](fa: ZIO[R, E, A])(f: E => ZIO[R, E, A]): ZIO[R, E, A] = fa.catchAll(f)
    }
}
