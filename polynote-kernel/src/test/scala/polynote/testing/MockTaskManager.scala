package polynote.testing

import polynote.kernel.{DoneStatus, TaskInfo}
import polynote.kernel.environment.CurrentTask
import polynote.kernel.logging.Logging
import polynote.kernel.task.TaskManager
import zio.blocking.Blocking
import zio.clock.Clock
import zio.internal.{ExecutionMetrics, Executor}
import zio.{Cause, Fiber, Has, RIO, Ref, Task, UIO, ZIO, ZLayer}

import java.util.concurrent.{Executors, ThreadPoolExecutor}

class MockTaskManager extends TaskManager.Service {
  val threadPool = Executors.newFixedThreadPool(1).asInstanceOf[ThreadPoolExecutor]
  val executor = Executor.fromThreadPoolExecutor(_ => 100)(threadPool)

  override def queue[R <: CurrentTask, A, R1 >: R <: Has[_]](id: String, label: String, detail: String, errorWith: Cause[Throwable] => TaskInfo => TaskInfo)(task: RIO[R, A])(implicit ev: R1 with CurrentTask <:< R): RIO[R1, Task[A]] =
    task.provideSomeLayer[R1](CurrentTask.none).lock(executor).forkDaemon.map(_.join)

  override def run[R <: Has[_], A](id: String, label: String, detail: String, errorWith: Cause[Throwable] => TaskInfo => TaskInfo)(task: RIO[CurrentTask with R, A]): RIO[R, A] =
    task.provideSomeLayer[R](CurrentTask.none)

  override def runSubtask[R <: CurrentTask, A](id: String, label: String, detail: String, errorWith: Cause[Throwable] => TaskInfo => TaskInfo)(task: RIO[R, A]): RIO[R, A] =
    task.provideSomeLayer[R](CurrentTask.none)

  override def register(id: String, label: String, detail: String, parent: Option[String], errorWith: DoneStatus)(cancelCallback: ((TaskInfo => TaskInfo) => Unit) => ZIO[Logging, Nothing, Unit]): RIO[Blocking with Clock with Logging, Fiber[Throwable, Unit]] = {
    for {
      runtime  <- ZIO.runtime[Any]
      taskInfo  = TaskInfo("dummy")
      infoRef  <- Ref.make(taskInfo)
      onUpdate  = (fn: TaskInfo => TaskInfo) => runtime.unsafeRun(infoRef.update(fn))
      task      = cancelCallback(onUpdate).zipRight(infoRef.get).repeatUntil(_.status.isDone)
      process  <- task.unit.forkDaemon
    } yield process
  }

  override def cancelAll(): UIO[Unit] = ZIO.unit

  override def cancelTask(id: String): UIO[Unit] = ZIO.unit

  override def shutdown(): UIO[Unit] = ZIO.unit

  override def list: UIO[List[TaskInfo]] = ZIO.succeed(Nil)
}

object MockTaskManager {
  def layer: ZLayer[Any, Nothing, TaskManager] = ZLayer.succeed(new MockTaskManager)
}
