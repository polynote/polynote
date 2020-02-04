package polynote.testing

import polynote.config.PolynoteConfig
import polynote.env.ops.Enrich
import polynote.kernel.Kernel.Factory
import polynote.kernel.{BaseEnv, CellEnv, GlobalEnv, Kernel, ResultValue, interpreter}
import polynote.kernel.environment.{Config, Env, NotebookUpdates}
import interpreter.Interpreter
import polynote.kernel.logging.Logging
import zio.blocking.Blocking
import zio.clock.Clock
import zio.console.Console
import zio.internal.{Platform, PlatformLive}
import zio.random.Random
import zio.system.System
import zio.{RIO, Runtime, ZIO}

trait ZIOSpecBase[Env] extends Runtime[Env] {
  type Environment = Env

  // TODO: should test platform behave differently? Isolate per suite?
  val Platform: Platform = PlatformLive.Default
    .withReportFailure(_ => ()) // suppress printing error stack traces by default

  implicit class IORunOps[A](val self: ZIO[Environment, Throwable, A]) {
    def runIO(): A = ZIOSpecBase.this.runIO(self)
  }

  implicit class IORunWithOps[R, A](val self: ZIO[R, Throwable, A]) {
    def runWith[R1](env: R1)(implicit ev: Environment with R1 <:< R, enrich: Enrich[Environment, R1]): A =
      ZIOSpecBase.this.runIO(self.provide(enrich(Environment, env)))
  }

  def runIO[A](io: ZIO[Environment, Throwable, A]): A = unsafeRunSync(io).getOrElse {
    c => throw c.squash
  }

}

trait ZIOSpec extends ZIOSpecBase[Clock with Console with System with Random with Blocking with Logging] {
  // TODO: mock the pieces of this
  val Environment: Environment =
    new Clock.Live with Console.Live with System.Live with Random.Live with Blocking.Live with Logging.Live

  implicit class ConfigIORunOps[A](val self: ZIO[Environment with Config, Throwable, A]) {
    def runWithConfig(config: PolynoteConfig): A = ZIOSpec.this.runIO(self.provide(Env.enrichWith[Environment, Config](Environment, Config.of(config))))
  }
}

trait ConfiguredZIOSpec extends ZIOSpecBase[BaseEnv with Config] {
  def config: PolynoteConfig = PolynoteConfig()

  val Environment: BaseEnv with Config = new Blocking.Live with Clock.Live with System.Live with Logging.Live with Config {
    override val polynoteConfig: PolynoteConfig = config
  }
}

trait ExtConfiguredZIOSpec[Env] extends ZIOSpecBase[BaseEnv with Config with Env] {
  def config: PolynoteConfig = PolynoteConfig()

  val baseEnv: BaseEnv with Config = new Blocking.Live with Clock.Live with System.Live with Logging.Live with Config {
    override val polynoteConfig: PolynoteConfig = config
  }
}

object ValueMap {
  def unapply(values: List[ResultValue]): Option[Map[String, Any]] = Some(apply(values))
  def apply(values: List[ResultValue]): Map[String, Any] = values.map(v => v.name -> v.value).toMap
}
