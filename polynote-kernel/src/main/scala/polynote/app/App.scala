package polynote.app

import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

import polynote.kernel.environment.Env
import polynote.kernel.logging.Logging
import zio.blocking.Blocking
import zio.clock.Clock
import zio.console.Console
import zio.internal.stacktracer.Tracer
import zio.internal.stacktracer.impl.AkkaLineNumbersTracer
import zio.internal.tracing.TracingConfig
import zio.internal.{Executor, Platform, Tracing}
import zio.random.Random
import zio.system.System
import zio.{BootstrapRuntime, Cause, IO, Runtime, Task, ZIO, ZLayer, system}

trait App extends zio.App {
  type Environment = zio.ZEnv with Logging
  implicit val runtime: Runtime[Any] = this

  def main(args: List[String]): ZIO[Environment, Nothing, Int]

  override final def run(args: List[String]): ZIO[zio.ZEnv, Nothing, Int] = main(args).provideCustomLayer(Logging.live)
}