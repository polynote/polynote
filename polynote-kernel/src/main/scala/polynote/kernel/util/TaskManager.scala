package polynote.kernel.util

import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedDeque, LinkedBlockingQueue}

import scala.collection.JavaConverters._
import cats.syntax.all._
import cats.instances.list._
import cats.effect.concurrent.{Deferred, Ref, Semaphore}
import cats.effect.{ContextShift, Fiber, IO}
import fs2.Stream
import fs2.concurrent.Queue
import polynote.kernel.{KernelStatusUpdate, TaskInfo, TaskStatus, UpdatedTasks}

/**
  * Manages running tasks. Announces task status to `statusUpdates`. Tasks can be run concurrently or queued to run
  * one at a time.
  */
// TODO: This should get some cleanup while retaining the external semantics
class TaskManager(
  statusUpdates: Publish[IO, KernelStatusUpdate],
  semaphore: Semaphore[IO])(implicit
  contextShift: ContextShift[IO]
) {
  import TaskManager.{Queued, Running, QueuedStream}

  private val running = new ConcurrentLinkedDeque[TaskManager.Running[_]]()

  // TODO: This LinkedBlockingQueue (and runNext()) ought to be replaceable by an fs2 Queue.
  private val queuedTasks = new LinkedBlockingQueue[TaskManager.Queued[_]]()

  private def publish(taskInfo: TaskInfo): IO[Unit] = statusUpdates.publish1(UpdatedTasks(taskInfo :: Nil))

  private def run[A](taskInfo: TaskInfo, task: IO[A]): IO[Running[A]] = {
    for {
      _       <- publish(taskInfo)
      fiber   <- task.start
      runTask  = Running(taskInfo, fiber)
      _       <- IO(running.add(runTask))
    } yield runTask
  }

  private def runNext(): IO[Unit] = (IO(queuedTasks.take()) <* semaphore.acquire).flatMap {
    queued =>
      System.err.println(s"Running ${queued.taskInfo.label}")
      run(queued.taskInfo.running, queued.io).flatMap {
        runTask =>
          def finalizer: IO[Unit] =
            IO(running.remove(runTask)) *>
              publish(queued.taskInfo.completed) *>
                (runNext().start >>= runQueueFiber.set) *>
                  semaphore.release

          runTask.fiber.join.uncancelable.flatMap {
            result =>
              // The "result" might be a thing (i.e. Stream) that's still running, so we need to defer the finalizer
              // until after it completes. Subtypes of Queued know how to do that for their type, using onComplete.
              queued.onComplete(result, finalizer)
          }.attempt.flatMap {
            resultOrError =>
              // Finally, the "result" with the onComplete behavior needs to be completed into the Queued task's promise
              // If it failed, the finalizer also needs to be run eagerly now, because there is no result to which we can attach that behavior.
              queued.deferred.complete(resultOrError) *> {
                resultOrError match {
                  case Left(err) => finalizer
                  case Right(_)  => IO.unit
                }
              }
          }
      }
  }

  private val runQueueFiber: Ref[IO, Fiber[IO, Unit]] = Ref.unsafe(runNext().start.unsafeRunSync())

  /**
    * Cancel all queued tasks
    */
  def cancelAllQueued: IO[Unit] = IO {
    val javaList = new java.util.ArrayList[Queued[_]](queuedTasks.size())
    queuedTasks.drainTo(javaList)
    javaList.asScala.toList
  }.flatMap {
    dequeuedTasks => dequeuedTasks.map {
      queued => publish(queued.taskInfo.completed)
    }.sequence.as(())
  }

  /**
    * Queue a task which will result in a Stream. The next queued task will not start until the resulting stream
    * terminates.
    */
  def queueTaskStream[A](id: String, label: String, detail: String = "")(fn: TaskInfo => IO[Stream[IO, A]]): IO[IO[Stream[IO, A]]] = {
    val taskInfo = TaskInfo(id, label, detail, TaskStatus.Queued)
    publish(taskInfo) *> Deferred[IO, Either[Throwable, Stream[IO, A]]].flatMap {
      deferred => IO(queuedTasks.put(QueuedStream(taskInfo, fn(taskInfo), deferred))).map {
        _ => deferred.get.flatMap {
          case Left(err) => IO.raiseError(err)
          case Right(result) => IO.pure(result)
        }
      }
    }
  }

  /**
    * Run a task by passing a function which will receive the [[TaskInfo]] and return a value. This bypasses the
    * task queue.
    *
    * @return an IO, which will upon evaluation will "block" until the task runs and completes.
    */
  def runTask[A](id: String, label: String, detail: String = "")(fn: TaskInfo => A): IO[A] =
    runTaskIO(id, label, detail)((taskInfo: TaskInfo) => IO.pure(taskInfo).map(fn))

  /**
    * Run a task by passing a function which will receive the [[TaskInfo]] and return an IO suspension which itself
    * evaluates to a value. This bypasses the task queue.
    *
    * @return an IO, which will upon evaluation will "block" until the task runs and completes.
    */
  def runTaskIO[A](id: String, label: String, detail: String = "")(fn: TaskInfo => IO[A]): IO[A] = {
    val taskInfo = TaskInfo(id, label, detail, TaskStatus.Queued)
    statusUpdates.publish1(UpdatedTasks(taskInfo :: Nil)).unsafeRunAsyncAndForget()

    val eval = for {
      _       <- IO.cancelBoundary
      runInfo  = taskInfo.copy(status = TaskStatus.Running)
      runTask <- run(runInfo, fn(runInfo))
      result  <- runTask.fiber.join.uncancelable.guarantee(IO(running.remove(runTask)))
    } yield result

    eval.guarantee {
      publish(taskInfo.completed)
    }
  }

  def allTasks: IO[List[TaskInfo]] = for {
    current  <- IO(running.iterator().asScala.map(_.taskInfo).toList)
    queued   <- IO(queuedTasks.iterator().asScala.map(_.taskInfo).toList)
  } yield current ++ queued

  def runningTasks: IO[List[TaskInfo]] = IO(running.iterator().asScala.map(_.taskInfo).toList)

  def shutdown(): IO[Unit] = runQueueFiber.get.flatMap(_.cancel.map(_ => ()) *> cancelAllQueued *> running.iterator.asScala.toList.map(_.fiber.cancel).parSequence.as(()))

}

object TaskManager {

  /**
    * A queued task of type A
    */
  sealed trait Queued[A] {
    type Result = A

    // Describes the information about the task
    def taskInfo: TaskInfo

    // Describes the computation that the task will run
    def io: IO[A]

    // A promise to accept the result (or failure) of the task
    def deferred: Deferred[IO, Either[Throwable, A]]

    /**
      * Attach the given finalizer so that it will be evaluated at the "completion" of a. What this means depends on
      * A. For example, a Stream would run the finalizer at the completion of the stream; if A were itself i.e IO[X]
      * then it would attach the finalizer via {{a <* finalizer}}.
      *
      * @return An IO suspension of a new [[A]] value, which is the given value with the finalizer attached.
      */
    def onComplete(a: A, finalizer: IO[Unit]): IO[A]
  }

  private case class QueuedStream[A](
    taskInfo: TaskInfo, io: IO[Stream[IO, A]], deferred: Deferred[IO, Either[Throwable, Stream[IO, A]]]
  ) extends Queued[Stream[IO, A]] {
    override def onComplete(a: Stream[IO, A], finalizer: IO[Unit]): IO[Stream[IO, A]] =
      IO.pure(a.onFinalize(finalizer))
  }

  private case class Running[A](taskInfo: TaskInfo, fiber: Fiber[IO, A])

  def apply(statusUpdates: Publish[IO, KernelStatusUpdate])(implicit contextShift: ContextShift[IO]): IO[TaskManager] =
    Semaphore[IO](1).map {
      semaphore => new TaskManager(statusUpdates, semaphore)
    }
}