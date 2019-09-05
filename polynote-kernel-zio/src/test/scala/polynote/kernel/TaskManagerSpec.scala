package polynote.kernel

import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue

import cats.effect.concurrent.Ref
import fs2.Pipe
import org.scalatest.{FreeSpec, Matchers}
import polynote.kernel.TaskStatus.{Complete, Queued, Running}
import polynote.kernel.util.Publish
import polynote.messages.TinyList
import polynote.testing.{MockPublish, ZIOSpec}
import zio.{DefaultRuntime, Semaphore, Task, ZIO}

import scala.collection.JavaConverters._

class TaskManagerSpec extends FreeSpec with Matchers with ZIOSpec {
  private val debug = true

  "queues tasks" - {

    "runs queued tasks sequentially" in {
      val mockPublish = new MockPublish[KernelStatusUpdate]
      val taskManager = TaskManager(mockPublish).runIO()
      @volatile var state = 0
      val task1 = zio.blocking.effectBlocking {
        if (debug) println(s"${Instant.now()} running 1")
        Thread.sleep(10)
        state = 1
        if (debug) println(s"${Instant.now()} completed 1")
      }.provide(runtime.Environment)

      val task2 = zio.blocking.effectBlocking {
        if (debug) println(s"${Instant.now()} running 2")
        state shouldEqual 1
        Thread.sleep(10)
        state = 2
        if (debug) println(s"${Instant.now()} completed 2")
      }.provide(runtime.Environment)

      val task3 = zio.blocking.effectBlocking {
        if (debug) println(s"${Instant.now()} running 3")
        state shouldEqual 2
        Thread.sleep(10)
        state = 3
        if (debug) println(s"${Instant.now()} completed 3")
      }.provide(runtime.Environment)

      val await1 = taskManager.queue_("1")(task1).runIO()
      val await2 = taskManager.queue_("2")(task2).runIO()
      val await3 = taskManager.queue_("3")(task3).runIO()

      // shouldn't matter in which order they're awaited
      await1.runIO()
      await3.runIO()
      await2.runIO()

      val taskInfos = mockPublish.toList.runIO().collect {
        case UpdatedTasks(TinyList(taskInfo :: Nil)) => taskInfo
      }

      // we can only guarantee ordering of status updates to a certain degree.
      val groupedTaskInfos = taskInfos.zipWithIndex.groupBy(_._1.id.toString)

      // any updates for a given task should be in order
      groupedTaskInfos.values.foreach {
        updates => updates.sortBy(_._1.status) shouldEqual updates.sortBy(_._2)
      }

      // 2 and 3 should both queue before 2 starts running
      val queued2 = groupedTaskInfos("2").head
      val queued3 = groupedTaskInfos("3").head
      queued2._1 shouldEqual TaskInfo("2", "2", "", Queued, 0)
      queued3._1 shouldEqual TaskInfo("3", "3", "", Queued, 0)

      val completed1 = groupedTaskInfos("1").find(_._1.status == Complete).get

      // 2 and 3 should both be queued before 1 completes
      completed1._2 should be > queued2._2
      completed1._2 should be > queued3._2

    }

    "interrupts running tasks and cancels queued tasks before they run" in {
      val mockPublish = new MockPublish[KernelStatusUpdate]
      val taskManager = TaskManager(mockPublish).runIO()

      @volatile var state = 0
      val task1 = zio.blocking.effectBlocking {
        if (debug) println(s"${Instant.now()} running 1")
        Thread.sleep(100000)
        state = 1
        if (debug) println(s"${Instant.now()} completed 1")
      }.provide(runtime.Environment)

      val task2 = ZIO {
        if (debug) println(s"${Instant.now()} running 2")
        state = 2
      }

      val await1 = taskManager.queue_("1")(task1).runIO()
      val await2 = taskManager.queue_("2")(task2).runIO()

      Thread.sleep(200)

      runtime.unsafeRunAsync_(taskManager.cancelAll())

      an [InterruptedException] should be thrownBy {
        await1.runIO()
      }

      an [InterruptedException] should be thrownBy {
        await2.runIO()
      }

      state shouldEqual 0
    }

  }

}
