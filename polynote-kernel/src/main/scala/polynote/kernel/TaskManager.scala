package polynote.kernel

import java.util.concurrent.{ConcurrentHashMap, LinkedBlockingQueue}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}

import scala.collection.JavaConverters._
import cats.effect.concurrent.Ref
import cats.syntax.traverse._
import cats.instances.list._
import fs2.concurrent.SignallingRef
import polynote.env.ops.Enrich
import polynote.kernel.environment.{CurrentTask, Env}
import polynote.kernel.logging.Logging
import polynote.kernel.util.Publish
import polynote.messages.TinyString
import zio.blocking.Blocking
import zio.clock.Clock
import zio.{Cause, Fiber, Promise, Queue, Semaphore, Task, RIO, UIO, ZIO, ZSchedule}
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
    def queue[R <: CurrentTask, A, R1 >: R](id: String, label: String = "", detail: String = "", errorWith: Cause[Throwable] => TaskInfo => TaskInfo = cause => _.failed(cause))(task: RIO[R, A])(implicit ev: R1 with CurrentTask =:= R, enrich: Enrich[R1, CurrentTask]): RIO[R1, Task[A]]

    /**
      * This overload is more useful if the return type needs to be inferred
      */
    def queue_[A, R](id: String, label: String = "", detail: String = "")(task: RIO[R with CurrentTask, A])(implicit enrich: Enrich[R, CurrentTask]): RIO[R, Task[A]] =
      queue[R with CurrentTask, A, R](id, label, detail)(task)

    /**
      * Run the given task. The task will be independent of the task queue, but will have access to a [[TaskInfo]]
      * reference; it can update this reference to broadcast task updates. The first update will be broadcast when the
      * task is evaluated, and a completion update will be broadcast when it completes or fails.
      */
    def run[R <: CurrentTask, A, R1 >: R](id: String, label: String = "", detail: String = "", errorWith: Cause[Throwable] => TaskInfo => TaskInfo = cause => _.failed(cause))(task: RIO[R, A])(implicit ev: R1 with CurrentTask =:= R, enrich: Enrich[R1, CurrentTask]): RIO[R1, A]

    /**
      * Run the given task as a subtask of the current task.
      * @see [[run]]
      */
    def runSubtask[R <: CurrentTask, A](id: String, label: String = "", detail: String = "", errorWith: Cause[Throwable] => TaskInfo => TaskInfo = cause => _.failed(cause))(task: RIO[R, A])(implicit enrich: Enrich[R, CurrentTask]): RIO[R, A]

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
      *
      * Returns the [[Fiber]] which updates the task status. Interrupting this fiber results in the cancellation task
      * returned from cancelCallback being evaluated.
      */
    def register(id: String, label: String = "", detail: String = "", parent: Option[String] = None, errorWith: DoneStatus = ErrorStatus)(cancelCallback: ((TaskInfo => TaskInfo) => Unit) => ZIO[Logging, Nothing, Unit]): RIO[Blocking with Clock with Logging, Fiber[Throwable, Unit]]

    /**
      * Cancel all tasks. If a task has not yet begun running, it will simply be cancelled. If a task is already running,
      * it will be interrupted. (see [[ZIO.interrupt]])
      */
    def cancelAll(): UIO[Unit]

    /**
      * Shut down the task manager and stop executing any tasks in the queue. Cancels all running tasks (see [[cancelAll]])
      */
    def shutdown(): UIO[Unit]

    /**
      * List all currently running tasks
      */
    def list: UIO[List[TaskInfo]]
  }

  private class Impl (
    queueing: Semaphore,
    statusUpdates: Publish[Task, KernelStatusUpdate],
    readyQueue: zio.Queue[(Promise[Throwable, Unit], Promise[Throwable, Unit])],
    process: Fiber[Throwable, Nothing]
  ) extends Service {

    private val taskCounter = new AtomicLong(0)
    private val tasks = new ConcurrentHashMap[String, (Ref[Task, TaskInfo], Fiber[Throwable, Any], Long)]
    private val updates = statusUpdates.contramap(UpdatedTasks.one)

    private def lbl(id: String, label: String) = if (label.isEmpty) id else label

    override def queue[R <: CurrentTask, A, R1 >: R](id: String, label: String = "", detail: String = "", errorWith: Cause[Throwable] => TaskInfo => TaskInfo)(task: RIO[R, A])(implicit ev: R1 with CurrentTask =:= R, enrich: Enrich[R1, CurrentTask]): RIO[R1, Task[A]] = queueing.withPermit {
      for {
        statusRef     <- SignallingRef[Task, TaskInfo](TaskInfo(id, lbl(id, label), detail, Queued))
        remove         = ZIO.effectTotal(tasks.remove(id))
        fail           = (err: Cause[Throwable]) => statusRef.update(errorWith(err)).ensuring(remove).uninterruptible.orDie
        complete       = statusRef.update(_.completed).ensuring(remove).uninterruptible.orDie
        updater       <- statusRef.discrete
          .terminateAfter(_.status.isDone)
          .through(updates.publish)
          .compile.drain.uninterruptible.fork
        taskBody       = ZIO.absolve {
          task.provideSome[R1](enrich(_, CurrentTask.of(statusRef)))
            .either
            .tap(_.fold(err => fail(Cause.fail(err)), _ => complete)) <* updater.join
        }
        myTurn        <- Promise.make[Throwable, Unit]
        imDone        <- Promise.make[Throwable, Unit]
        _             <- readyQueue.offer((myTurn, imDone))
        runTask        = (myTurn.await *> statusRef.update(_.running) *> taskBody).ensuring(imDone.succeed(())).onTermination(fail)
        taskFiber     <- runTask.fork
        descriptor     = (statusRef, taskFiber, taskCounter.getAndIncrement())
        _             <- Option(tasks.put(id, descriptor)).map(_._2.interrupt).getOrElse(ZIO.unit)
      } yield taskFiber.join.interruptChildren.ensuring(remove)
    }

    private def runImpl[R <: CurrentTask, A, R1 >: R](
        id: String,
        label: String,
        detail: String,
        parent: Option[TinyString],
        errorWith: Cause[Throwable] => TaskInfo => TaskInfo)
      (task: RIO[R, A])
      (implicit ev: R1 with CurrentTask =:= R, enrich: Enrich[R1, CurrentTask]): RIO[R1, A] = for {
          statusRef     <- SignallingRef[Task, TaskInfo](TaskInfo(id, lbl(id, label), detail, Running, progress = 0, parent = parent))
            remove         = ZIO.effectTotal(tasks.remove(id))
            updater       <- statusRef.discrete
              .terminateAfter(_.status.isDone)
              .through(updates.publish)
              .compile.drain.uninterruptible.ensuring(remove).fork
            taskBody       = task.provideSome[R1](enrich(_, CurrentTask.of(statusRef)))
            taskFiber     <- (taskBody <* statusRef.update(_.completed) <* updater.join).onError(cause => statusRef.update(errorWith(cause)).orDie).supervised.fork
            descriptor     = (statusRef, taskFiber, taskCounter.getAndIncrement())
            _             <- Option(tasks.put(id, descriptor)).map(_._2.interrupt).getOrElse(ZIO.unit)
            result        <- taskFiber.join.onInterrupt(taskFiber.interrupt)
        } yield result

    override def run[R <: CurrentTask, A, R1 >: R](id: String, label: String = "", detail: String = "", errorWith: Cause[Throwable] => TaskInfo => TaskInfo)(task: RIO[R, A])(implicit ev: R1 with CurrentTask =:= R, enrich: Enrich[R1, CurrentTask]): RIO[R1, A] =
      runImpl(id, label, detail, None, errorWith)(task)

    override def runSubtask[R <: CurrentTask, A](id: String, label: String, detail: String, errorWith: Cause[Throwable] => TaskInfo => TaskInfo)(task: RIO[R, A])(implicit enrich: Enrich[R, CurrentTask]): RIO[R, A] =
      ZIO.accessM[CurrentTask](_.currentTask.get).map(_.id).flatMap {
        parent =>
          runImpl[R, A, R](id, label, detail, Some(parent), errorWith)(task)
      }

    override def register(id: String, label: String = "", detail: String = "", parent: Option[String], errorWith: DoneStatus)(cancelCallback: ((TaskInfo => TaskInfo) => Unit) => ZIO[Logging, Nothing, Unit]): RIO[Blocking with Clock with Logging, Fiber[Throwable, Unit]] =
      for {
        statusRef   <- SignallingRef[Task, TaskInfo](TaskInfo(id, lbl(id, label), detail, Running, progress = 0, parent = parent.map(TinyString(_))))
        updateTasks  = new LinkedBlockingQueue[TaskInfo => TaskInfo]()
        completed    = new AtomicBoolean(false)
        updater     <- statusRef.discrete
          .terminateAfter(_.status.isDone)
          .through(updates.publish)
          .compile.drain
          .uninterruptible
          .ensuring(ZIO.effectTotal(completed.set(true)))
          .fork
        onUpdate     = (fn: TaskInfo => TaskInfo) => updateTasks.put(fn)
        cancel       = cancelCallback(onUpdate)
        process     <- zio.blocking.effectBlocking(updateTasks.take()).flatMap(statusRef.update)
          .repeat(ZSchedule.doUntil(_ => completed.get()))
          .ensuring(ZIO.effectTotal(tasks.remove(id)))
          .onInterrupt(statusRef.update(_.done(errorWith)).orDie.ensuring(cancel))
          .fork
        descriptor   = (statusRef, process, taskCounter.getAndIncrement())
        _           <- Option(tasks.put(id, descriptor)).map(_._2.interrupt).getOrElse(ZIO.unit)
      } yield process

    override def cancelAll(): UIO[Unit] = {
      tasks.values().asScala.toList.reverse.map {
        case (status, fiber, _) => fiber.interrupt
      }.sequence zipPar readyQueue.takeAll.flatMap {
        brokenPromises => brokenPromises.map {
          case (ready, done) => ready.interrupt *> done.interrupt
        }.sequence
      }
    }.unit

    override def list: UIO[List[TaskInfo]] = tasks.values().asScala.toList.sortBy(_._3).map(_._1.get.orDie).sequence

    override def shutdown(): UIO[Unit] = cancelAll() *> process.interrupt.unit
  }

  def apply(
    statusUpdates: Publish[Task, KernelStatusUpdate]
  ): Task[TaskManager.Service] = for {
    queueing <- Semaphore.make(1)
    queue    <- Queue.unbounded[(Promise[Throwable, Unit], Promise[Throwable, Unit])]
    run      <- queue.take.flatMap {
      case (ready, done) => ready.succeed(()) *> done.await
    }.forever.fork
  } yield new Impl(queueing, statusUpdates, queue, run)

  def access: RIO[TaskManager, TaskManager.Service] = ZIO.access[TaskManager](_.taskManager)

  def of(service: Service): TaskManager = new TaskManager {
    val taskManager: Service = service
  }

  def queue[R <: CurrentTask, A, R1 >: R <: TaskManager](id: String, label: String = "", detail: String = "", errorWith: Cause[Throwable] => TaskInfo => TaskInfo = cause => _.failed(cause))(task: RIO[R, A])(implicit ev: R1 with CurrentTask =:= R, enrich: Enrich[R1, CurrentTask]): RIO[R1, Task[A]] =
    ZIO.access[TaskManager](_.taskManager).flatMap {
      taskManager => taskManager.queue[R, A, R1](id, label, detail, errorWith)(task)
    }

  def run[R <: CurrentTask, A, R1 >: R <: TaskManager](id: String, label: String = "", detail: String = "", errorWith: Cause[Throwable] => TaskInfo => TaskInfo = cause => _.failed(cause))(task: RIO[R, A])(implicit ev: R1 with CurrentTask =:= R, enrich: Enrich[R1, CurrentTask]): RIO[R1, A] =
    ZIO.access[TaskManager](_.taskManager).flatMap {
      taskManager => taskManager.run[R, A, R1](id, label, detail, errorWith)(task)
    }

  def runSubtask[R <: CurrentTask, A](id: String, label: String = "", detail: String = "", errorWith: Cause[Throwable] => TaskInfo => TaskInfo = cause => _.failed(cause))(task: RIO[R, A])(implicit enrich: Enrich[R, CurrentTask]): RIO[R with TaskManager, A] =
    ZIO.access[TaskManager](_.taskManager).flatMap {
      taskManager => taskManager.runSubtask[R, A](id, label, detail, errorWith)(task)
    }

  def runR[R <: TaskManager](id: String, label: String = "", detail: String = "", errorWith: DoneStatus = ErrorStatus): Runner[R] = Runner[R](id, label, detail, errorWith)

  case class Runner[R <: TaskManager](id: String, label: String, detail: String, errorWith: DoneStatus) {
    def apply[A](task: RIO[R with CurrentTask, A])(implicit enrich: Enrich[R, CurrentTask]): RIO[R with TaskManager, A] = run[R with CurrentTask, A, R](id, label, detail)(task)
  }

}
