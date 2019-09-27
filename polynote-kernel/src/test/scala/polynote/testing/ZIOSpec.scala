package polynote.testing

import polynote.kernel.ResultValue
import polynote.kernel.logging.Logging
import zio.blocking.Blocking
import zio.clock.Clock
import zio.console.Console
import zio.internal.{Platform, PlatformLive}
import zio.random.Random
import zio.system.System
import zio.{Runtime, ZIO}

trait ZIOSpec extends Runtime[Clock with Console with System with Random with Blocking with Logging] {
  type Environment = Clock with Console with System with Random with Blocking with Logging
  // TODO: mock the pieces of this
  val Environment: Environment =
    new Clock.Live with Console.Live with System.Live with Random.Live with Blocking.Live with Logging.Live

  // TODO: should test platform behave differently? Isolate per suite?
  val Platform: Platform = PlatformLive.Default


  implicit class IORunOps[A](val self: ZIO[Clock with Console with System with Random with Blocking with Logging, Throwable, A]) {
    def runIO(): A = unsafeRunSync(self).getOrElse {
      c => throw c.squash
    }
  }
}

object ValueMap {
  def unapply(values: List[ResultValue]): Option[Map[String, Any]] = Some(apply(values))
  def apply(values: List[ResultValue]): Map[String, Any] = values.map(v => v.name -> v.value).toMap
}
