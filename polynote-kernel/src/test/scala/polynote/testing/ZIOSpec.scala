package polynote.testing

import polynote.config.PolynoteConfig
import polynote.env.ops.Enrich
import polynote.kernel.Kernel.Factory
import polynote.kernel.{BaseEnv, CellEnv, GlobalEnv, Kernel, ResultValue, interpreter}
import polynote.kernel.environment.{Config, Env, NotebookUpdates}
import interpreter.Interpreter
import org.scalatest.{BeforeAndAfterAll, Suite}
import polynote.kernel.logging.Logging
import polynote.testing
import zio.blocking.Blocking
import zio.clock.Clock
import zio.console.Console
import zio.internal.Platform
import zio.random.Random
import zio.system.System
import zio.{Has, RIO, Runtime, Tagged, ZIO, ZLayer}

abstract class TestRuntime
object TestRuntime {
  val runtime: Runtime.Managed[zio.ZEnv with Logging] = ZIOSpecBase.runtime
  def fiberDump(): List[zio.Fiber.Dump] = runtime.unsafeRun(zio.Fiber.dumpAll).toList
}

trait ZIOSpecBase[Env <: Has[_]] {
  import ZIOSpecBase.BaseEnv
  type Environment = Env
  val baseLayer: ZLayer[Any, Nothing, BaseEnv] = ZIOSpecBase.baseLayer
  def envLayer: ZLayer[zio.ZEnv with Logging, Nothing, Env]
  val runtime: Runtime.Managed[BaseEnv] = ZIOSpecBase.runtime

  // TODO: should test platform behave differently? Isolate per suite?
  implicit class IORunOps[A](val self: ZIO[BaseEnv, Throwable, A]) {
    def runIO(): A = ZIOSpecBase.this.runIO(self)
  }

  implicit class IORunWithOps[R <: Has[_], A](val self: ZIO[R, Throwable, A]) {
    def runWith[R1](env: R1)(implicit ev: Env with Has[R1] <:< R, ev1: Tagged[R1], ev2: Tagged[Has[R1]], ev3: Tagged[Env]): A =
      ZIOSpecBase.this.runIO(self.provideSomeLayer[Env](ZLayer.succeed(env)).provideSomeLayer[BaseEnv](envLayer))
  }

  def runIO[A](io: ZIO[BaseEnv, Throwable, A]): A = runtime.unsafeRunSync(io).getOrElse {
    c => throw c.squash
  }


}

object ZIOSpecBase {

  type BaseEnv = zio.ZEnv with Logging
  val baseLayer: ZLayer[Any, Nothing, zio.ZEnv with Logging] = Clock.live ++ Console.live ++ System.live ++ Random.live ++ Blocking.live ++ (Blocking.live >>> Logging.live)
  val platform: Platform = Platform.default
    .withReportFailure(_ => ()) // suppress printing error stack traces by default
  val runtime: Runtime.Managed[zio.ZEnv with Logging] = Runtime.unsafeFromLayer(baseLayer, platform)
}

trait ZIOSpec extends ZIOSpecBase[Clock with Console with System with Random with Blocking with Logging] {
  override lazy val envLayer: ZLayer[zio.ZEnv, Nothing, Environment] = baseLayer
  implicit class ConfigIORunOps[A](val self: ZIO[Environment with Config, Throwable, A]) {
    def runWithConfig(config: PolynoteConfig): A = ZIOSpec.this.runIO(self.provideSomeLayer[Environment](ZLayer.succeed(config)))
  }
}

trait ConfiguredZIOSpec extends ZIOSpecBase[BaseEnv with Config] { this: Suite =>
  def config: PolynoteConfig = PolynoteConfig()
  override lazy val envLayer: ZLayer[zio.ZEnv, Nothing, BaseEnv with Config] =
    baseLayer ++ ZLayer.succeed(config)

  implicit class IORunOpsWithConfig[A](val self: ZIO[BaseEnv with Config, Throwable, A]) {
    def runIO(): A = ConfiguredZIOSpec.this.runIOConfig(self)
  }

  def runIOConfig[A](io: ZIO[BaseEnv with Config, Throwable, A]): A = runtime.unsafeRunSync(io.provideSomeLayer(envLayer)).getOrElse {
    c => throw c.squash
  }
}

trait ExtConfiguredZIOSpec[Env <: Has[_]] extends ZIOSpecBase[BaseEnv with Config with Env] {
  def tagged: Tagged[Env]
  def configuredEnvLayer: ZLayer[zio.ZEnv with Config, Nothing, Env]

  private implicit def _tagged: Tagged[Env] = tagged

  def config: PolynoteConfig = PolynoteConfig()
  lazy val configLayer: ZLayer[Any, Nothing, Config] = ZLayer.succeed(config)
  override final lazy val envLayer: ZLayer[zio.ZEnv, Nothing, BaseEnv with Config with Env] = baseLayer ++ Logging.live ++ ((baseLayer ++ configLayer) >>> configuredEnvLayer) ++ configLayer
}

object ValueMap {
  def unapply(values: List[ResultValue]): Option[Map[String, Any]] = Some(apply(values))
  def apply(values: List[ResultValue]): Map[String, Any] = values.map(v => v.name -> v.value).toMap
}
