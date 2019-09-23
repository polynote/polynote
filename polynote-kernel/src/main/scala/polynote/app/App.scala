package polynote.app

import polynote.kernel.logging.Logging
import zio.blocking.Blocking
import zio.clock.Clock
import zio.console.Console
import zio.internal.{Platform, PlatformLive}
import zio.random.Random
import zio.system.System
import zio.{IO, Runtime, Task, ZIO, system}

trait App extends Runtime[Clock with System with Blocking with Logging] {
  type Environment = Clock with system.System with Blocking with Logging
  val Environment: Environment = new Clock.Live with Console.Live with system.System.Live with Random.Live with Blocking.Live with Logging.Live
  val Platform: Platform       = PlatformLive.Default

  implicit val runtime: Runtime[Environment] = this

  def run(args: List[String]): ZIO[Environment, Nothing, Int]

  final def main(args0: Array[String]): Unit =
    try sys.exit(
      unsafeRun(
        for {
          fiber <- run(args0.toList).fork
            _ <- IO.effectTotal(java.lang.Runtime.getRuntime.addShutdownHook(new Thread {
              override def run(): Unit = {
                val _ = unsafeRunSync(fiber.interrupt)
              }
            }))
            result <- fiber.join
        } yield result
      )
    )
    catch { case _: SecurityException => }
}
