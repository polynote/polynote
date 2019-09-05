package polynote.kernel

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

import cats.effect.concurrent.Ref
import cats.syntax.traverse._
import cats.instances.list._
import fs2.concurrent.SignallingRef
import polynote.env.ops.Enrich
import polynote.kernel.environment.{CurrentTask, Env}
import polynote.kernel.util.Publish
import zio.blocking.Blocking
import zio.clock.Clock
import zio.{Fiber, Semaphore, Task, TaskR, UIO, ZIO, ZSchedule}
import zio.interop.catz._

trait TaskManager {
  val taskManager: TaskManager.Service
}

object TaskManager {

  trait Service {

    /**
      * Queue a task, which can access a reference to the TaskInfo and modify it to broadcast updates.
      * When the given task finishes, errors, or is interrupted, a completion message of the appropriate status will
      * be broadcast.
      *
      * Evaluating the returned outer [[Task]] results in queueing of the given task, which will eventually cause the
      * given task to be evaluated. Evaluating the inner [[Task]] results in blocking (asynchronously) until the given
      * task completes.
      *
      * Interrupting the inner Task results in cancelling the queued task, or interrupting it if it's running.
      *
      * Note that status updates are sent somewhat lazily, and for a series of rapid updates to the task status only the
      * last update might get sent.
      */
    def queue[R <: CurrentTask, A, R1 >: R](id: String, label: String = "", detail: String = "")(task: TaskR[R, A])(implicit ev: R1 with CurrentTask =:= R, enrich: Enrich[R1, CurrentTask]): TaskR[R1, Task[A]]

    /**
      * This overload is more useful if the return type needs to be inferred
      */
    def queue_[A, R](id: String, label: String = "", detail: String = "")(task: TaskR[R with CurrentTask, A])(implicit enrich: Enrich[R, CurrentTask]): TaskR[R, Task[A]] =
      queue[R with CurrentTask, A, R](id, label, detail)(task)

    /**
      * Convenience for [[queue]] when the result type is Unit.
      */
    def queueEffect[R <: CurrentTask, R1 >: R](id: String, label: String = "", detail: String = "")(task: TaskR[R, Unit])(implicit ev: R1 with CurrentTask =:= R, enrich: Enrich[R1, CurrentTask]): TaskR[R1, Task[Unit]] =
      queue[R, Unit, R1](id, label, detail)(task)

    /**
      * Register the given task. The task will be independent of the task queue, but will have access to a [[TaskInfo]]
      * reference; it can update this reference to broadcast task updates. The first update will be broadcast when the
      * task is evaluated, and a completion update will be broadcast when it completes or fails.
      */
    def run[R <: CurrentTask, A, R1 >: R](id: String, label: String = "", detail: String = "")(task: TaskR[R, A])(implicit ev: R1 with CurrentTask =:= R, enrich: Enrich[R1, CurrentTask]): TaskR[R1, A]

    /**
      * Register an external task for status broadcasting and cancellation, by providing a function which will receive a
      * function for modifying the [[TaskInfo]] and return a [[UIO]] that cancels the external task upon evaluation. The
      * cancellation task returned by the provided function may be evaluated even after the external task is finished.
      *
      * The [[TaskInfo]] should be updated by invoking the function (TaskInfo => TaskInfo) => Unit, passing as an argument
      * the function which updates the status (e.g. `_.progress(0.5)`).
      *
      * The external task must report itself as being finished by updating the [[TaskInfo]] to a completed or failed
      * state. All updates to the task reference will be broadcast. If [[cancelAll]] is run on this task manager, the
      * given task will be interrupted (if it has not yet reported completion) using the return cancellation task.
      */
    def register(id: String, label: String = "", detail: String = "")(cancelCallback: ((TaskInfo => TaskInfo) => Unit) => UIO[Unit]): TaskR[Blocking with Clock, Unit]

    /**
      * Cancel all tasks. If a task has not yet begun running, it will simply be cancelled. If a task is already running,
      * it will be interrupted. (see [[ZIO.interrupt]])
      */
    def cancelAll(): UIO[Unit]

    /**
      * List all currently running tasks
      */
    def list: UIO[List[TaskInfo]]
  }

  private class Impl (
    queueing: Semaphore,
    running: Semaphore,
    statusUpdates: Publish[Task, KernelStatusUpdate]
  ) extends Service {

    private val tasks = new Deque[(Ref[Task, TaskInfo], Fiber[Throwable, Any])]()
    private val updates = statusUpdates.contramap(UpdatedTasks.one)

    private def lbl(id: String, label: String) = if (label.isEmpty) id else label

    override def queue[R <: CurrentTask, A, R1 >: R](id: String, label: String = "", detail: String = "")(task: TaskR[R, A])(implicit ev: R1 with CurrentTask =:= R, enrich: Enrich[R1, CurrentTask]): TaskR[R1, Task[A]] = queueing.withPermit {
      for {
        statusRef     <- SignallingRef[Task, TaskInfo](TaskInfo(id, lbl(id, label), detail, TaskStatus.Queued))
        updater       <- statusRef.discrete
          .terminateAfter(_.status.isDone)
          .through(updates.publish)
          .compile.drain.fork
        taskBody       = task.provideSome[R1](enrich(_, CurrentTask.of(statusRef)))
        runTask        = running.withPermit(
          statusRef.update(_.running) *>
            ZIO.absolve(
              taskBody.either
                .tap(_.fold(_ => statusRef.update(_.failed), _ => statusRef.update(_.completed)))) <* updater.join
        )
        taskFiber     <- runTask.interruptChildren.fork
        taskPair       = (statusRef, taskFiber)
        _             <- tasks.add(taskPair).uninterruptible
      } yield taskFiber.join.onInterrupt(taskFiber.interrupt).ensuring(tasks.remove(taskPair))
    }

    override def run[R <: CurrentTask, A, R1 >: R](id: String, label: String = "", detail: String = "")(task: TaskR[R, A])(implicit ev: R1 with CurrentTask =:= R, enrich: Enrich[R1, CurrentTask]): TaskR[R1, A] =
      for {
        statusRef     <- SignallingRef[Task, TaskInfo](TaskInfo(id, lbl(id, label), detail, TaskStatus.Running))
        updater       <- statusRef.discrete
          .terminateAfter(_.status.isDone)
          .through(updates.publish)
          .compile.drain.fork
        taskBody       = task.provideSome[R1](enrich(_, CurrentTask.of(statusRef)))
        taskFiber     <- (taskBody <* statusRef.update(_.completed) <* updater.join).onError(_ => statusRef.update(_.failed).orDie).fork
        taskPair       = (statusRef, taskFiber)
        _             <- tasks.add(taskPair).uninterruptible
        result        <- taskFiber.join.onInterrupt(taskFiber.interrupt).ensuring(tasks.remove(taskPair))
      } yield result

    override def register(id: String, label: String = "", detail: String = "")(cancelCallback: ((TaskInfo => TaskInfo) => Unit) => UIO[Unit]): TaskR[Blocking with Clock, Unit] =
      for {
        statusRef   <- SignallingRef[Task, TaskInfo](TaskInfo(id, lbl(id, label), detail, TaskStatus.Running))
        updateTasks  = new LinkedBlockingQueue[TaskInfo => TaskInfo]()
        completed    = new AtomicBoolean(false)
        updater     <- statusRef.discrete
          .terminateAfter(_.status.isDone)
          .through(updates.publish)
          .onFinalize(ZIO(completed.set(true)).orDie)
          .compile.drain.uninterruptible.fork
        onUpdate     = (fn: TaskInfo => TaskInfo) => updateTasks.put(fn)
        cancel       = cancelCallback(onUpdate)
        process     <- zio.blocking.blocking(statusRef.update(updateTasks.take()))
          .repeat(ZSchedule.doUntil(_ => completed.get()))
          .onInterrupt(cancel.ensuring(statusRef.update(_.failed).orDie))
          .fork
        taskPair     = (statusRef, process)
        _           <- tasks.add(taskPair).const(taskPair).bracket(tasks.remove)(_._2.join)
      } yield ()

    override def cancelAll(): UIO[Unit] = tasks.reverseDrainM(_._2.interrupt.unit)

    override def list: UIO[List[TaskInfo]] = tasks.toList.flatMap(_.map(_._1.get.orDie).sequence)
  }

  def apply(
    statusUpdates: Publish[Task, KernelStatusUpdate]
  ): Task[TaskManager.Service] = for {
    queueing <- Semaphore.make(1)
    running  <- Semaphore.make(1)
  } yield new Impl(queueing, running, statusUpdates)


  def queue[R <: CurrentTask, A, R1 >: R <: TaskManager](id: String, label: String = "", detail: String = "")(task: TaskR[R, A])(implicit ev: R1 with CurrentTask =:= R, enrich: Enrich[R1, CurrentTask]): TaskR[R1, Task[A]] =
    ZIO.access[TaskManager](_.taskManager).flatMap {
      taskManager => taskManager.queue[R, A, R1](id, label, detail)(task)
    }

  def run[R <: CurrentTask, A, R1 >: R <: TaskManager](id: String, label: String = "", detail: String = "")(task: TaskR[R, A])(implicit ev: R1 with CurrentTask =:= R, enrich: Enrich[R1, CurrentTask]): TaskR[R1, A] =
    ZIO.access[TaskManager](_.taskManager).flatMap {
      taskManager => taskManager.run[R, A, R1](id, label, detail)(task)
    }

}
