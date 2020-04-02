package polynote.app

import polynote.kernel.logging.Logging
import polynote.kernel.networking.Networking
import zio.{Runtime, ZIO}

trait App extends zio.App {
  type Environment = zio.ZEnv with Logging with Networking
  implicit val runtime: Runtime[Any] = this

  def main(args: List[String]): ZIO[Environment, Nothing, Int]

  override final def run(args: List[String]): ZIO[zio.ZEnv, Nothing, Int] = main(args).provideCustomLayer(Logging.live ++ Networking.live.orDie)
}