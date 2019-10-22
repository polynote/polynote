package polynote.app

import java.util.Collections

import polynote.kernel.logging.Logging
import zio.blocking.Blocking
import zio.clock.Clock
import zio.console.Console
import zio.internal.stacktracer.Tracer
import zio.internal.stacktracer.impl.AkkaLineNumbersTracer
import zio.internal.tracing.TracingConfig
import zio.internal.{Executor, Platform, PlatformLive, Tracing}
import zio.random.Random
import zio.system.System
import zio.{Cause, IO, Runtime, Task, ZIO, system}

trait App extends Runtime[Clock with System with Blocking with Logging] {
  type Environment = Clock with system.System with Blocking with Logging
  val Environment: Environment = new Clock.Live with Console.Live with system.System.Live with Random.Live with Blocking.Live with Logging.Live

  protected def reportFailure(cause: Cause[_]): Unit =
    if (!cause.interrupted)
      unsafeRun(Environment.logging.error(cause.prettyPrint))

  val Platform: Platform = new Platform {
    val executor: Executor = Executor.makeDefault(2048)

    val tracing = Tracing(Tracer.globallyCached(new AkkaLineNumbersTracer), TracingConfig.enabled)

    def fatal(t: Throwable): Boolean =
      t.isInstanceOf[VirtualMachineError]

    def reportFatal(t: Throwable): Nothing = {
      t.printStackTrace()
      try {
        java.lang.System.exit(-1)
        throw t
      } catch { case _: Throwable => throw t }
    }

    def reportFailure(cause: Cause[_]): Unit = App.this.reportFailure(cause)

    def newWeakHashMap[A, B](): java.util.Map[A, B] =
      Collections.synchronizedMap(new java.util.WeakHashMap[A, B]())

  }

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
