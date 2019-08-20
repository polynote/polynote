package polynote.testing

import polynote.kernel.interpreter.PublishResults
import polynote.kernel.{CurrentRuntime, CurrentTask, KernelStatusUpdate, Result}
import zio.Runtime
import zio.blocking.Blocking
import zio.clock.Clock
import zio.console.Console
import zio.random.Random
import zio.system.System

class RuntimeDelegate(runtime: Runtime[Clock with Console with System with Random with Blocking]) extends Clock
    with Console
    with System
    with Random
    with Blocking {
  val clock: Clock.Service[Any] = runtime.Environment.clock
  val console: Console.Service[Any] = runtime.Environment.console
  val system: System.Service[Any] = runtime.Environment.system
  val random: Random.Service[Any] = runtime.Environment.random
  val blocking: Blocking.Service[Any] = runtime.Environment.blocking
}

