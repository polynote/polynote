package polynote.kernel.interpreter

import cats.effect.concurrent.Ref
import fs2.concurrent.SignallingRef
import polynote.kernel.{CurrentRuntime, CurrentTask, KernelStatusUpdate, Output, Result, TaskInfo, UpdatedTasks}
import polynote.runtime.KernelRuntime
import polynote.testing.{MockPublish, RuntimeDelegate}
import zio.{Runtime, Task, TaskR, ZIO}
import zio.blocking.Blocking
import zio.clock.Clock
import zio.console.Console
import zio.random.Random
import zio.system.System
import zio.interop.catz._

class MockEnv(
  runtime: Runtime[Clock with Console with System with Random with Blocking],
  taskInfoRef: SignallingRef[Task, TaskInfo]
) extends RuntimeDelegate(runtime)
  with PublishResults
  with CurrentTask
  with CurrentRuntime {

  val results: MockPublish[Result] = new MockPublish[Result]
  val updates: MockPublish[KernelStatusUpdate] = new MockPublish[KernelStatusUpdate]
  val publishResult: Result => Task[Unit] = results.publish1
  val currentTask: Ref[Task, TaskInfo] = taskInfoRef
  val currentRuntime: KernelRuntime = new KernelRuntime(
    new KernelRuntime.Display {
      def content(contentType: String, content: String): Unit = runtime.unsafeRunSync(publishResult(Output(contentType, content)))
    },
    (frac, detail) => runtime.unsafeRunSync {
      for {
        _ <- currentTask.update(task => (if (detail != null && detail != "") task.copy(detail = detail) else task).progress(frac))
        task <- currentTask.get
        _ <- updates.publish1(UpdatedTasks(task :: Nil))
      } yield ()
    },
    statusOpt => ()
  )
}

object MockEnv {
  def apply(initialTask: TaskInfo): TaskR[Clock with Console with System with Random with Blocking, MockEnv] = ZIO.runtime[Clock with Console with System with Random with Blocking].flatMap {
    runtime => SignallingRef[Task, TaskInfo](initialTask).map {
      ref => new MockEnv(runtime, ref)
    }
  }
}