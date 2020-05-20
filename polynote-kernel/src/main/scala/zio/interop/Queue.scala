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

import cats.effect.LiftIO
import zio.{ Queue => ZioQueue, Runtime, UIO, ZEnv }

object Queue {

  /**
   * @see ZioQueue.bounded
   */
  final def bounded[F[+_], A](capacity: Int)(implicit R: Runtime[ZEnv], F: LiftIO[F]): F[Queue[F, A]] =
    create(ZioQueue.bounded[A](capacity))

  /**
   * @see ZioQueue.dropping
   */
  final def dropping[F[+_], A](capacity: Int)(implicit R: Runtime[ZEnv], F: LiftIO[F]): F[Queue[F, A]] =
    create(ZioQueue.dropping[A](capacity))

  /**
   * @see ZioQueue.sliding
   */
  final def sliding[F[+_], A](capacity: Int)(implicit R: Runtime[ZEnv], F: LiftIO[F]): F[Queue[F, A]] =
    create(ZioQueue.sliding[A](capacity))

  /**
   * @see ZioQueue.unbounded
   */
  final def unbounded[F[+_], A](implicit R: Runtime[ZEnv], F: LiftIO[F]): F[Queue[F, A]] =
    create(ZioQueue.unbounded[A])

  private final def create[F[+_], A](in: UIO[ZioQueue[A]])(implicit R: Runtime[ZEnv], F: LiftIO[F]): F[Queue[F, A]] =
    toEffect(in.map(new CQueue[F, A, A](_)))
}
