package polynote.testing

import polynote.kernel.ResultValue
import zio.blocking.Blocking
import zio.clock.Clock
import zio.console.Console
import zio.internal.{Platform, PlatformLive}
import zio.random.Random
import zio.system.System
import zio.{Runtime, ZIO}

trait ZIOSpec {
  val runtime: Runtime[Clock with Console with System with Random with Blocking] = new Runtime[Clock with Console with System with Random with Blocking] {
    // TODO: mock the pieces of this
    val Environment: Clock with Console with System with Random with Blocking =
      new Clock.Live with Console.Live with System.Live with Random.Live with Blocking.Live

    // TODO: should test platform behave differently? Isolate per suite?
    val Platform: Platform = PlatformLive.Default
  }

  implicit class IORunOps[A](val self: ZIO[Clock with Console with System with Random with Blocking, Throwable, A]) {
    def runIO(): A = runtime.unsafeRunSync(self).getOrElse {
      c => c.failures match {
        case first :: rest => throw first
        case Nil =>
          if (c.interrupted) throw new InterruptedException
          println(c)
          throw new RuntimeException(s"Failure or cause $c")
      }
    }
  }
}

object ValueMap {
  def unapply(values: List[ResultValue]): Option[Map[String, Any]] = Some(values.map(v => v.name -> v.value).toMap)
}
