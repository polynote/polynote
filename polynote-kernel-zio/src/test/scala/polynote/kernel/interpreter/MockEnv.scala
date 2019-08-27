package polynote.kernel
package interpreter

import cats.effect.concurrent.Ref
import fs2.concurrent.SignallingRef
import polynote.messages.CellID
import polynote.runtime.KernelRuntime
import polynote.testing.MockPublish
import zio.{Runtime, Task, TaskR, ZIO}
import zio.blocking.Blocking
import zio.clock.Clock
import zio.console.Console
import zio.random.Random
import zio.system.System
import zio.interop.catz._

case class MockEnv(
  baseEnv: BaseEnv,
  cellID: CellID,
  currentTask: SignallingRef[Task, TaskInfo],
  results: MockPublish[Result],
  updates: MockPublish[KernelStatusUpdate],
  runtime: Runtime[Any]
) extends CellEnvT {
  val clock: Clock.Service[Any] = baseEnv.clock
  val blocking: Blocking.Service[Any] = baseEnv.blocking
  val system: System.Service[Any] = baseEnv.system
  val publishResult: Result => Task[Unit] = results.publish1
  val publishStatus: KernelStatusUpdate => Task[Unit] = updates.publish1
  val currentRuntime: KernelRuntime = runtime.unsafeRun(CurrentRuntime.from(cellID, publishResult, publishStatus, currentTask)).currentRuntime

  def toCellEnv(classLoader: ClassLoader): CellEnvironment = runtime.unsafeRun(CellEnvironment.from(this).mkExecutor(classLoader))
}

object MockEnv {
  def apply(cellID: Int): TaskR[BaseEnv, MockEnv] = for {
    env <- ZIO.access[BaseEnv](identity)
    runtime <- ZIO.runtime[Any]
    currentTask <- SignallingRef[Task, TaskInfo](TaskInfo(s"Cell$cellID"))
  } yield new MockEnv(env, CellID(cellID), currentTask, new MockPublish, new MockPublish, runtime)
}