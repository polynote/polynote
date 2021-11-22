package polynote.kernel

import polynote.kernel.environment.{CurrentTask, PublishStatus, TaskRef}
import polynote.kernel.logging.Logging
import polynote.kernel.util.UPublish
import polynote.messages.TinyString
import zio.blocking.Blocking
import zio.clock.Clock
import zio.stream.SubscriptionRef
import zio.{Cause, Fiber, Has, Promise, Queue, RIO, Semaphore, Task, UIO, URIO, ZIO, ZLayer, ZManaged}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import scala.collection.JavaConverters._

package object task {

  type TaskManager = Has[TaskManager.Service]

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
      def queue[R <: CurrentTask, A, R1 >: R <: Has[_]](
        id: String,
        label: String = "",
        detail: String = "",
        errorWith: Cause[Throwable] => TaskInfo => TaskInfo = cause => _.failed(cause)
      )(task: RIO[R, A])(implicit ev: R1 with CurrentTask <:< R): RIO[R1, Task[A]]

      /**
        * This overload is more useful if the return type needs to be inferred
        */
      def queue_[A, R <: Has[_]](id: String, label: String = "", detail: String = "")(task: RIO[R with CurrentTask, A]): RIO[R, Task[A]] =
        queue[R with CurrentTask, A, R](id, label, detail)(task)

      /**
        * Run the given task. The task will be independent of the task queue, but will have access to a [[TaskInfo]]
        * reference; it can update this reference to broadcast task updates. The first update will be broadcast when the
        * task is evaluated, and a completion update will be broadcast when it completes or fails.
        */
      def run[R <: Has[_], A](id: String, label: String = "", detail: String = "", errorWith: Cause[Throwable] => TaskInfo => TaskInfo = cause => _.failed(cause))(task: RIO[CurrentTask with R, A]): RIO[R, A]

      /**
        * Run the given task as a subtask of the current task.
        * @see [[run]]
        */
      def runSubtask[R <: CurrentTask, A](id: String, label: String = "", detail: String = "", errorWith: Cause[Throwable] => TaskInfo => TaskInfo = cause => _.failed(cause))(task: RIO[R, A]): RIO[R, A]

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
        * Cancel the task with the given ID, and all currently queued or running tasks which were queued or started
        * after the given task was queued or started.
        */
      def cancelTask(id: String): UIO[Unit]

      /**
        * Shut down the task manager and stop executing any tasks in the queue. Cancels all running tasks (see [[cancelAll]])
        */
      def shutdown(): UIO[Unit]

      /**
        * List all currently running tasks
        */
      def list: UIO[List[TaskInfo]]
    }

    private case class QueuedTask(id: String, ready: Promise[Throwable, Unit], done: Promise[Throwable, Unit])
    private case class TaskDescriptor(
      id: String,
      taskInfoRef: TaskRef,
      fiber: Fiber[Throwable, Any],
      counter: Long,
      ready: Option[Promise[Throwable, Unit]]
    ) {
      def cancel: UIO[Unit] = {
        val unqueued = ready match {
          case Some(promise) => promise.interrupt
          case None          => ZIO.succeed(false)
        }

        ZIO.unlessM(unqueued)(fiber.interrupt)
      }
    }

    private class Impl (
      queueing: Semaphore,
      statusUpdates: UPublish[KernelStatusUpdate],
      readyQueue: zio.Queue[QueuedTask],
      process: Fiber[Throwable, Nothing]
    ) extends Service {

      private val taskCounter = new AtomicLong(0)
      private val tasks = new ConcurrentHashMap[String, TaskDescriptor]
      private val updates = statusUpdates.contramap(UpdatedTasks.one)

      private def lbl(id: String, label: String) = if (label.isEmpty) id else label

      override def queue[R <: CurrentTask, A, R1 >: R <: Has[_]](
        id: String, label: String = "",
        detail: String = "",
        errorWith: Cause[Throwable] => TaskInfo => TaskInfo
      )(task: RIO[R, A])(implicit ev: R1 with CurrentTask <:< R): RIO[R1, Task[A]] = queueing.withPermit {
        for {
          statusSubRef  <- SubscriptionRef.make(TaskInfo(id, lbl(id, label), detail, Queued))
          statusRef      = statusSubRef.ref
          remove         = ZIO.effectTotal(tasks.remove(id))
          myTurn        <- Promise.make[Throwable, Unit]
          imDone        <- Promise.make[Throwable, Unit]
          fail           = (err: Cause[Throwable]) => statusRef.update(t => ZIO.succeed(errorWith(err)(t))).ensuring(remove &> imDone.interrupt).uninterruptible
          complete       = statusRef.update(t => ZIO.succeed(t.completed)).ensuring(remove).uninterruptible
          updater       <- statusSubRef.changes.foreachWhile(t => updates.publish(t).as(!t.status.isDone)).uninterruptible.forkDaemon
          taskBody       = ZIO.absolve {
            task.provideSomeLayer[R1](ZLayer.succeed[TaskRef](statusRef))
              .either
              .tap(_.fold(err => fail(Cause.fail(err)), _ => complete)) <* updater.join
          }
          _             <- readyQueue.offer(QueuedTask(id, myTurn, imDone))
          wait           = myTurn.await.onInterrupt(imDone.interrupt)
          runTask        = (wait *> statusRef.update(t => ZIO.succeed(t.running)) *> taskBody)
            .onTermination(fail)
            .ensuring(imDone.succeed(()))
          _             <- statusRef.get >>= updates.publish
          taskFiber     <- runTask.ensuring(remove).forkDaemon
          descriptor     = TaskDescriptor(id, statusRef, taskFiber, taskCounter.getAndIncrement(), Some(myTurn))
          _             <- Option(tasks.put(id, descriptor)).map(_.cancel).getOrElse(ZIO.unit)
        } yield taskFiber.join
      }

      private def runImpl[R <: Has[_], A](
        task: RIO[R with CurrentTask, A],
        id: String,
        label: String,
        detail: String,
        parent: Option[TinyString],
        errorWith: Cause[Throwable] => TaskInfo => TaskInfo
      ): RIO[R, A] = for {
        statusSubRef  <- SubscriptionRef.make(TaskInfo(id, lbl(id, label), detail, Running, progress = 0, parent = parent))
        statusRef      = statusSubRef.ref
        remove         = ZIO.effectTotal(tasks.remove(id))
        updater       <- statusSubRef.changes
          .foreachWhile(t => updates.publish(t).as(!t.status.isDone))
          .uninterruptible
          .ensuring(remove).fork
        taskBody       = task
          .onInterrupt(fibers => statusRef.update(t => ZIO.succeed(errorWith(Cause.interrupt(fibers.headOption.getOrElse(Fiber.Id.None)))(t))).ignore.unit)
          .provideSomeLayer[R](CurrentTask.layer(statusRef))
        _             <- statusRef.get >>= updates.publish
        taskFiber     <- (taskBody <* statusRef.update(t => ZIO.succeed(t.completed)) <* updater.join)
          .onError(cause => statusRef.update(t => ZIO.succeed(errorWith(cause)(t))))
          .onInterrupt(fibers => statusRef.update(t => ZIO.succeed(errorWith(Cause.interrupt(fibers.head))(t))))
          .fork
        descriptor     = TaskDescriptor(id, statusRef, taskFiber, taskCounter.getAndIncrement(), None)
        _             <- Option(tasks.put(id, descriptor)).map(_.cancel).getOrElse(ZIO.unit)
        result        <- taskFiber.join.onInterrupt(taskFiber.interrupt)
      } yield result

      override def run[R <: Has[_], A](id: String, label: String = "", detail: String = "", errorWith: Cause[Throwable] => TaskInfo => TaskInfo)(task: RIO[CurrentTask with R, A]): RIO[R, A] =
        runImpl[R, A](task, id, label, detail, None, errorWith)

      override def runSubtask[R <: CurrentTask, A](id: String, label: String, detail: String, errorWith: Cause[Throwable] => TaskInfo => TaskInfo)(task: RIO[R, A]): RIO[R, A] =
        ZIO.accessM[CurrentTask](_.get.get).map(_.id).flatMap {
          parent =>
            runImpl[R, A](task, id, label, detail, Some(parent), errorWith)
        }

      override def register(id: String, label: String = "", detail: String = "", parent: Option[String], errorWith: DoneStatus)(cancelCallback: ((TaskInfo => TaskInfo) => Unit) => ZIO[Logging, Nothing, Unit]): RIO[Blocking with Clock with Logging, Fiber[Throwable, Unit]] =
        for {
          runtime      <- ZIO.runtime[Any]
          statusSubRef <- SubscriptionRef.make(TaskInfo(id, lbl(id, label), detail, Running, progress = 0, parent = parent.map(TinyString(_))))
          statusRef     = statusSubRef.ref
          updateTasks  <- Queue.unbounded[TaskInfo => TaskInfo]
          completed     = new AtomicBoolean(false)
          updater      <- statusSubRef.changes
            .foreachWhile(t => updates.publish(t).as(!t.status.isDone))
            .uninterruptible
            .ensuring(ZIO.effectTotal(completed.set(true)))
            .forkDaemon
          onUpdate      = (fn: TaskInfo => TaskInfo) => runtime.unsafeRun(updateTasks.offer(fn).unit)
          cancel        = cancelCallback(onUpdate)
          process      <- updateTasks.take.flatMap(updater => statusRef.update(t => ZIO.succeed(updater(t))) *> statusRef.get)
            .repeatUntil(_.status.isDone).unit
            .ensuring(ZIO.effectTotal(tasks.remove(id)))
            .onInterrupt(statusRef.update(t => ZIO.succeed(t.done(errorWith))).ensuring(cancel))
            .forkDaemon
          descriptor    = TaskDescriptor(id, statusRef, process, taskCounter.getAndIncrement(), None)
          _            <- Option(tasks.put(id, descriptor)).map(_.cancel).getOrElse(ZIO.unit)
        } yield process

      override def cancelAll(): UIO[Unit] = {
        val runningFibers    = ZIO.effectTotal(tasks.values().asScala.map(_.fiber))
        val interruptRunning = runningFibers.flatMap {
          fibers => ZIO.foreachPar_(fibers)(_.interruptFork)
        }

        val interruptQueued = for {
          promises <- readyQueue.takeAll
          _        <- ZIO.foreachPar_(promises) {
            case QueuedTask(_, ready, done) => ready.interrupt &> done.interrupt
          }
        } yield ()

        interruptQueued *> interruptRunning
      }

      override def cancelTask(id: String): UIO[Unit] =
        ZIO.effectTotal(Option(tasks.get(id))).some.flatMap {
          descriptor =>
            val tasksAfter = tasks.values().asScala.toSeq.filter(_.counter > descriptor.counter).sortBy(-_.counter)
            ZIO.foreachPar_(tasksAfter)(_.cancel) *> descriptor.cancel
        }.orElseSucceed(())

      override def list: UIO[List[TaskInfo]] = ZIO.foreach(tasks.values().asScala.toList.sortBy(_.counter))(_.taskInfoRef.get)

      override def shutdown(): UIO[Unit] = cancelAll() *> readyQueue.shutdown *> process.interrupt.unit
    }

    def apply(
      statusUpdates: UPublish[KernelStatusUpdate]
    ): Task[TaskManager.Service] = for {
      queueing <- Semaphore.make(1)
      queue    <- Queue.unbounded[QueuedTask]
      run      <- (ZIO.yieldNow *> ZIO.allowInterrupt *> queue.take).flatMap {
        case QueuedTask(_, ready, done) => ready.succeed(()) *> done.await.run
      }.forever.forkDaemon
    } yield new Impl(queueing, statusUpdates, queue, run)

    def make: ZManaged[PublishStatus, Throwable, TaskManager.Service] = for {
      statusUpdates <- ZIO.access[PublishStatus](_.get).toManaged_
      taskManager   <- apply(statusUpdates).toManaged(_.shutdown())
    } yield taskManager

    def layer: ZLayer[PublishStatus, Throwable, TaskManager] = ZLayer.fromManaged(make)

    def access: URIO[TaskManager, TaskManager.Service] = ZIO.access[TaskManager](_.get)

    def of(service: Service): TaskManager = Has(service)

    def queue[R <: CurrentTask, A, R1 >: R <: TaskManager](
      id: String,
      label: String = "",
      detail: String = "",
      errorWith: Cause[Throwable] => TaskInfo => TaskInfo = cause => _.failed(cause)
    )(task: RIO[R, A])(implicit ev: R1 with CurrentTask <:< R): RIO[R1, Task[A]] =
      access.flatMap(_.queue[R, A, R1](id, label, detail, errorWith)(task))

    def run[R <: TaskManager, A](id: String, label: String = "", detail: String = "", errorWith: Cause[Throwable] => TaskInfo => TaskInfo = cause => _.failed(cause))(task: RIO[CurrentTask with R, A]): RIO[R, A] =
      access.flatMap(_.run[R, A](id, label, detail, errorWith)(task))

    def runSubtask[R <: CurrentTask, A](id: String, label: String = "", detail: String = "", errorWith: Cause[Throwable] => TaskInfo => TaskInfo = cause => _.failed(cause))(task: RIO[R, A]): RIO[R with TaskManager, A] =
      access.flatMap(_.runSubtask[R, A](id, label, detail, errorWith)(task))

  }

}
