package polynote.app

import polynote.kernel.logging.Logging
import zio.{ExitCode, Runtime, ZIO}

trait App extends zio.App {
  implicit val runtime: Runtime[Any] = this

  def main(args: List[String]): ZIO[Environment, Nothing, Int]

  override final def run(args: List[String]): ZIO[zio.ZEnv, Nothing, ExitCode] = main(args).provideCustomLayer(Logging.live).map(ExitCode.apply)
}