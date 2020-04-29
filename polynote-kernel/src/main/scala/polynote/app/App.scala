package polynote.app

import polynote.kernel.logging.Logging
import zio.{Runtime, ZIO}

trait App extends zio.App {
  implicit val runtime: Runtime[Any] = this

  def main(args: List[String]): ZIO[Environment, Nothing, Int]

  override final def run(args: List[String]): ZIO[zio.ZEnv, Nothing, Int] = main(args).provideCustomLayer(Logging.live)
}