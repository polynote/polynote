package polynote.kernel

import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiFunction

import cats.effect.IO
import fs2.concurrent.Topic
import org.apache.spark.Success
import org.apache.spark.scheduler._
import polynote.kernel.util.Publish

class KernelListener(statusUpdates: Publish[IO, KernelStatusUpdate]) extends SparkListener {

  private val jobStageIds = new ConcurrentHashMap[Int, Set[Int]]
  private val stageInfos = new ConcurrentHashMap[Int, StageInfo]()
  private val stageTasksCompleted = new ConcurrentHashMap[Int, Int]()

  private def stageTaskId(stageInfo: StageInfo) = s"Stage ${stageInfo.stageId}"


  /**
    * When a job is started, it jots down all of the stages that belong to that job.
    * The for each stage, it stores the stage info under that job ID, and publishes a queued status for the stage.
    */
  override def onJobStart(jobStart: SparkListenerJobStart): Unit = jobStart match {
    case SparkListenerJobStart(jobId, time, jobStages, properties) =>
      cleanupStages(jobId)

      jobStageIds.put(jobId, jobStages.map(_.stageId).toSet)

      // TODO: This can be a HUGE amount of queued stages for some tasks... maybe there's a good way to give some idea
      //       of how many stages you're going to wait for without displaying them all at job start
//      jobStages.foreach {
//        stageInfo =>
//          stageInfos.put(stageInfo.stageId, stageInfo)
//          statusUpdates.publish1(UpdatedTasks(TaskInfo(stageTaskId(stageInfo), s"Stage ${stageInfo.stageId}", stageInfo.name, TaskStatus.Queued) :: Nil)).unsafeRunAsyncAndForget()
//      }
  }

  /**
    * @see [[cleanupStages()]]
    */
  override def onJobEnd(jobEnd: SparkListenerJobEnd): Unit = cleanupStages(jobEnd.jobId)

  /**
    * When a job finishes, it checks whether there are any stages still tracked under the job ID. If so, it
    * cleans them up and publishes a Complete status for each of them.
    */
  private def cleanupStages(jobId: Int): Unit = {
    if (jobStageIds.contains(jobId)) {
      jobStageIds.getOrDefault(jobId, Set.empty).foreach {
        stageId =>
          Option(stageInfos.remove(stageId)).foreach {
            stageInfo =>
              statusUpdates.publish1(UpdatedTasks(TaskInfo(stageTaskId(stageInfo), "", "", TaskStatus.Complete, 255.toByte) :: Nil)).unsafeRunAsyncAndForget()
          }
      }
    }
  }

  /**
    * When a stage starts, it publishes a Running status for that stage.
    */
  override def onStageSubmitted(stageSubmitted: SparkListenerStageSubmitted): Unit = stageSubmitted match {
    case SparkListenerStageSubmitted(stageInfo, _) =>
      stageInfos.put(stageInfo.stageId, stageInfo)
      statusUpdates.publish1(UpdatedTasks(TaskInfo(stageTaskId(stageInfo), s"Stage ${stageInfo.stageId}", stageInfo.name, TaskStatus.Running) :: Nil)).unsafeRunAsyncAndForget()
  }

  /**
    * When a stage finishes, it publishes a Complete status for the stage.
    */
  override def onStageCompleted(stageCompleted: SparkListenerStageCompleted): Unit = stageCompleted match {
    case SparkListenerStageCompleted(stageInfo) =>
      stageInfos.remove(stageInfo.stageId)
      statusUpdates.publish1(UpdatedTasks(TaskInfo(stageTaskId(stageInfo), "", "", TaskStatus.Complete, 255.toByte) :: Nil)).unsafeRunAsyncAndForget()
  }

  /**
    * Nothing to be done when a task starts (we're not publishing status messages for individual tasks)
    */
  override def onTaskStart(taskStart: SparkListenerTaskStart): Unit = ()

  /**
    * When a task completes, it increments the number of completed tasks for the stage, and recomputes the progress.
    * Then it publishes a new Running status with the new progress.
    */
  override def onTaskEnd(taskEnd: SparkListenerTaskEnd): Unit = taskEnd.reason match {
    case Success =>
      stageTasksCompleted.putIfAbsent(taskEnd.stageId, 0)
      stageTasksCompleted.computeIfPresent(taskEnd.stageId, new BiFunction[Int, Int, Int] {
        override def apply(t: Int, u: Int): Int = u + 1
      })

      for {
        stageInfo <- Option(stageInfos.get(taskEnd.stageId))
        completed <- Option(stageTasksCompleted.getOrDefault(taskEnd.stageId, 0)).filter(_ != 0)
      } yield {
        val progress = math.round(completed.toDouble / stageInfo.numTasks * 255).toByte
        statusUpdates.publish1(UpdatedTasks(TaskInfo(stageTaskId(stageInfo), s"Stage ${stageInfo.stageId}", stageInfo.name, TaskStatus.Running, progress) :: Nil)).unsafeRunAsyncAndForget()
      }
    case _ =>
  }

}
