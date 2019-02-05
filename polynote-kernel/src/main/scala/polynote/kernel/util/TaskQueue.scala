package polynote.kernel.util

import java.util.concurrent.ConcurrentHashMap

import scala.collection.JavaConverters._
import cats.syntax.all._
import cats.instances.list._
import cats.effect.concurrent.{Ref, Semaphore}
import cats.effect.{ContextShift, Fiber, IO}
import polynote.kernel.{KernelStatusUpdate, TaskInfo, TaskStatus, UpdatedTasks}

/**
  * A task queue in which one task can be running concurrently. Announces task status to `statusUpdates`.
  */
class TaskQueue(
  statusUpdates: Publish[IO, KernelStatusUpdate],
  semaphore: Semaphore[IO],
  currentTaskRef: Ref[IO, Option[TaskQueue.Running]])(implicit
  contextShift: ContextShift[IO]
) {
  import TaskQueue.{Queued, Running}

  private val waitingTasks = new ConcurrentHashMap[String, Queued]()

  /**
    * Cancel the task with the given ID, if it hasn't started running.
    *
    * @return an IO which upon evaluation will return a Boolean indicating whether the task was successfully cancelled.
    */
  def cancelQueuedTask(id: String): IO[Boolean] = Option(waitingTasks.remove(id)).map {
    case Queued(taskInfo, io) =>
      val update = UpdatedTasks(taskInfo.copy(status = TaskStatus.Complete) :: Nil) // TODO: separate status for cancelled?
      val cancel = io.start.flatMap(_.cancel)

      (cancel, statusUpdates.publish1(update)).parMapN((_, _) => true)
  }.getOrElse(IO.pure(false))

  /**
    * Cancel all queued tasks
    */
  def cancelAllQueued: IO[Unit] = waitingTasks.keys().asScala.toList.map(cancelQueuedTask).parSequence.map(_ => ())

  /**
    * Attempts to cancel the task with the given ID. If it is the currently running task, whether or not it can be
    * cancelled depends on the nature of the task.
    *
    * @return an IO which upon evaluation will return a Boolean indicating whether the cancel attempt was made. This is
    *         true if the task was either currently running or was queued, and false if no such task existed. A true value
    *         does not necessarily indicate that the task was successfully cancelled; only that an attempt to cancel it
    *         was made.
    */
  def cancelTask(id: String): IO[Boolean] = {
    val cancelCurrent = currentTaskRef.get.flatMap {
      case Some(Running(taskInfo, fiber)) if taskInfo.id == id => fiber.cancel.map(_ => true)
      case _ => IO.pure(false)
    }

    val cancelQueued = cancelQueuedTask(id)

    (cancelCurrent, cancelQueued).parMapN(_ || _)
  }

  /**
    * Run a task by passing a function which will receive the [[TaskInfo]] and return a value. Note that the task is
    * immediately queued, so this method side effects (eagerly, outside of IO) by queueing the task and notifying
    * the topic.
    *
    * Because the function is opaque to IO, it's unlikely that it will be cancelable.
    *
    * @return an IO, which will upon evaluation will "block" until the task runs and completes.
    */
  def runTask[A](id: String, label: String, detail: String = "")(fn: TaskInfo => A): IO[A] =
    runTaskIO(id, label, detail)((taskInfo: TaskInfo) => IO.pure(taskInfo).map(fn))

  /**
    * Run a task by passing a function which will receive the [[TaskInfo]] and return an IO suspension which itself
    * evaluates to a value. Note that the task is immediately queued, so this method side effects (eagerly, outside of
    * IO) by queueing the task and notifying the topic.
    *
    * Cancellation of the task is dependent on its internal evaluation structure; cancellation might only succeed at
    * suspension boundaries within the task.
    * @see [[IO.cancelBoundary]]
    *
    * @return an IO, which will upon evaluation will "block" until the task runs and completes.
    */
  def runTaskIO[A](id: String, label: String, detail: String = "")(fn: TaskInfo => IO[A]): IO[A] = {
    val taskInfo = TaskInfo(id, label, detail, TaskStatus.Queued)
    statusUpdates.publish1(UpdatedTasks(taskInfo :: Nil)).unsafeRunAsyncAndForget()

    val run = semaphore.acquire.bracket { _ =>
      for {
        _      <- IO.cancelBoundary
        runInfo = taskInfo.copy(status = TaskStatus.Running)
        _      <- statusUpdates.publish1(UpdatedTasks(runInfo :: Nil))
        fiber  <- IO.cancelBoundary.flatMap(_ => fn(taskInfo)).start
        _      <- currentTaskRef.set(Some(Running(runInfo, fiber.asInstanceOf[Fiber[IO, Any]])))
        _       = waitingTasks.remove(id)
        done    = ReadySignal()
        join    = fiber.join.guarantee(currentTaskRef.set(None)).guarantee(done.complete)
// TODO: another way of doing the below
//        update <- statusUpdates.subscribe(32)
//          .collect { case UpdatedTasks(tasks) => tasks.filter(ti => ti.id == id && ti.status != TaskStatus.Complete) }
//          .flatMap(Stream.emits)
//          .evalMap[IO, Unit](updated => currentTaskRef.update(opt => opt.filter(_.taskInfo.id == id).map(_.copy(taskInfo = updated)).orElse(opt)))
//          .interruptWhen[IO](done()).compile.drain.start
        result <- fiber.join.guarantee(currentTaskRef.set(None)).guarantee(done.complete)
//        _      <- update.join
      } yield result
    } {
      _ => semaphore.release
    }.guarantee {
      statusUpdates.publish1(UpdatedTasks(taskInfo.copy(status = TaskStatus.Complete, progress = 255.toByte) :: Nil))
    }


    waitingTasks.put(id, Queued(taskInfo, run))
    run
  }

  def allTasks: IO[List[TaskInfo]] = for {
    current <- currentTaskRef.get.map(_.map(_.taskInfo))
    queued   = waitingTasks.elements().asScala.toList.map(_.taskInfo)
  } yield current.toList ++ queued

  def currentTask: IO[Option[TaskInfo]] = currentTaskRef.get.map(_.map(_.taskInfo))

}

object TaskQueue {
  private case class Queued(taskInfo: TaskInfo, io: IO[Any])
  private case class Running(taskInfo: TaskInfo, fiber: Fiber[IO, Any])

  def apply(statusUpdates: Publish[IO, KernelStatusUpdate])(implicit contextShift: ContextShift[IO]): IO[TaskQueue] = for {
    semaphore <- Semaphore[IO](1)
    ref       <- Ref[IO].of[Option[Running]](None)
  } yield new TaskQueue(statusUpdates, semaphore, ref)
}