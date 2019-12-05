package polynote.testing

import polynote.config.PolynoteConfig
import polynote.env.ops.Enrich
import polynote.kernel.ResultValue
import polynote.kernel.environment.{Config, Env}
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

  implicit class ConfigIORunOps[A](val self: ZIO[Environment with Config, Throwable, A]) {
    def runIO(config: PolynoteConfig): A = self.provideSomeM(Env.enrich[Environment](Config.of(config))).runIO()
  }

  implicit class TwoEnvIORunOps[R1, R2, A](val self: ZIO[Environment with R1 with R2, Throwable, A]) {
    def runIO(env1: R1, env2: R2)(implicit enrich1: Enrich[Environment, R1], enrich2: Enrich[Environment with R1, R2]): A =
      self.provideSome[Environment](env => enrich2(enrich1(env, env1), env2)).runIO()
  }

  implicit class EnvIORunOps[R, A](val self: ZIO[Environment with R, Throwable, A]) {
    def runIO(env1: R)(implicit enrich1: Enrich[Environment, R]): A =
      self.provideSome[Environment](env => enrich1(env, env1)).runIO()
  }

  implicit class IORunOps[A](val self: ZIO[Environment, Throwable, A]) {
    def runIO(): A = unsafeRunSync(self).getOrElse {
      c => throw c.squash
    }
  }
}

object ValueMap {
  def unapply(values: List[ResultValue]): Option[Map[String, Any]] = Some(apply(values))
  def apply(values: List[ResultValue]): Map[String, Any] = values.map(v => v.name -> v.value).toMap
}
