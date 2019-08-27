package polynote.kernel

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

import cats.effect.concurrent.Ref
import fs2.concurrent.SignallingRef
import polynote.kernel.util.Publish
import zio.blocking.Blocking
import zio.clock.Clock
import zio.{Fiber, Semaphore, Task, TaskR, UIO, ZIO, ZSchedule}
import zio.interop.catz._

class TaskManager[R] private (
  enrichR: (R, Ref[Task, TaskInfo]) => R with CurrentTask,
  queueing: Semaphore,
  running: Semaphore,
  statusUpdates: Publish[Task, KernelStatusUpdate]
) {

  private val tasks = new Deque[Fiber[Throwable, Any]]()
  private val updates = statusUpdates.contramap(UpdatedTasks.one)

  private def lbl(id: String, label: String) = if (label.isEmpty) id else label

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
  def queue[A](id: String, label: String = "", detail: String = "")(task: TaskR[R with CurrentTask, A]): TaskR[R, TaskR[R, A]] = queueing.withPermit {
    for {
      statusRef     <- SignallingRef[Task, TaskInfo](TaskInfo(id, lbl(id, label), detail, TaskStatus.Queued))
      updater       <- statusRef.discrete
        .terminateAfter(_.status.isDone)
        .through(updates.publish)
        .compile.drain.fork
      taskBody       = task.provideSome[R](r => enrichR(r, statusRef))
      runTask        = running.withPermit(
        statusRef.update(_.running) *>
          ZIO.absolve(
            taskBody.either
              .tap(_.fold(_ => statusRef.update(_.failed), _ => statusRef.update(_.completed)))) <* updater.join
      )
      taskFiber     <- runTask.interruptChildren.fork
      _             <- tasks.add(taskFiber).uninterruptible
    } yield taskFiber.join.onInterrupt(taskFiber.interrupt).ensuring(tasks.remove(taskFiber))
  }

  /**
    * Register the given task. The task will be independent of the task queue, but will have access to a [[TaskInfo]]
    * reference; it can update this reference to broadcast task updates. The first update will be broadcast when the
    * task is evaluated, and a completion update will be broadcast when it completes or fails.
    */
  def run[A](id: String, label: String = "", detail: String = "")(task: TaskR[R with CurrentTask, A]): TaskR[R, A] =
    for {
      statusRef     <- SignallingRef[Task, TaskInfo](TaskInfo(id, lbl(id, label), detail, TaskStatus.Running))
      updater       <- statusRef.discrete
        .terminateAfter(_.status.isDone)
        .through(updates.publish)
        .compile.drain.fork
      taskFiber     <- (task.interruptChildren.provideSome[R](enrichR(_, statusRef)) <* statusRef.update(_.completed) <* updater.join).onError(_ => statusRef.update(_.failed).orDie).fork
      _             <- tasks.add(taskFiber).uninterruptible
      result        <- taskFiber.join.onInterrupt(taskFiber.interrupt).ensuring(tasks.remove(taskFiber))
    } yield result

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
  def register(id: String, label: String = "", detail: String = "")(cancelCallback: ((TaskInfo => TaskInfo) => Unit) => UIO[Unit]): TaskR[Blocking with Clock, Unit] =
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
      _           <- tasks.add(process).const(process).bracket(tasks.remove)(_.join)
    } yield ()

  def cancelAll(): UIO[Unit] = tasks.reverseDrainM(_.interrupt.unit)
}

object TaskManager {
  def apply[R](
    enrichR: (R, Ref[Task, TaskInfo]) => R with CurrentTask,
    statusUpdates: Publish[Task, KernelStatusUpdate]
  ): Task[TaskManager[R]] = for {
    queueing <- Semaphore.make(1)
    running  <- Semaphore.make(1)
  } yield new TaskManager(enrichR, queueing, running, statusUpdates)

  trait Provider[R] {
    val taskManager: TaskManager[R]
  }

  def queue[A, R]()
}
