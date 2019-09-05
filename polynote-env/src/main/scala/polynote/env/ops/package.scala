package polynote.env

import polynote.env.macros.ZEnvMacros
import zio.ZIO

package object ops {
  implicit class Ops[R, E, A](val self: ZIO[R, E, A]) extends AnyVal {
    def provideOne[Remainder]: ProvideOne[Remainder, R, E, A] = new ProvideOne(self)
  }

  class ProvideOne[Remainder, R, E, A](val self: ZIO[R, E, A]) extends AnyVal {
    def apply[T](value: T)(implicit ev: Remainder with T <:< R): ZIO[Remainder, E, A] = macro ZEnvMacros.provideOne[T, R, E, A, Remainder]
  }
}
