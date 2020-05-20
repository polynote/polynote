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

package zio.interop.console

import _root_.cats.Show
import zio.ZIO
import zio.console.Console

object cats {

  /**
   * Prints the string representation of an object to the console.
   */
  def putStr[A](a: A)(implicit ev: Show[A]): ZIO[Console, Nothing, Unit] =
    zio.console.putStr(ev.show(a))

  /**
   * Prints the string representation of an object to the console, including a newline character.
   */
  def putStrLn[A](a: A)(implicit ev: Show[A]): ZIO[Console, Nothing, Unit] =
    zio.console.putStrLn(ev.show(a))
}
