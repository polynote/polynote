package polynote.kernel

import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiFunction

import cats.effect.IO
import fs2.concurrent.Topic
import org.apache.spark.Success
import org.apache.spark.scheduler._

class KernelListener(statusUpdates: Topic[IO, KernelStatusUpdate]) extends SparkListener {

  private val jobStageIds = new ConcurrentHashMap[Int, Set[Int]]
  private val stageInfos = new ConcurrentHashMap[Int, StageInfo]()
  private val stageTasksCompleted = new ConcurrentHashMap[Int, Int]()

  private def stageTaskId(stageInfo: StageInfo) = s"Stage ${stageInfo.stageId}"

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

  override def onStageSubmitted(stageSubmitted: SparkListenerStageSubmitted): Unit = stageSubmitted match {
    case SparkListenerStageSubmitted(stageInfo, _) =>
      stageInfos.put(stageInfo.stageId, stageInfo)
      statusUpdates.publish1(UpdatedTasks(TaskInfo(stageTaskId(stageInfo), s"Stage ${stageInfo.stageId}", stageInfo.name, TaskStatus.Running) :: Nil)).unsafeRunAsyncAndForget()
  }

  override def onStageCompleted(stageCompleted: SparkListenerStageCompleted): Unit = stageCompleted match {
    case SparkListenerStageCompleted(stageInfo) =>
      stageInfos.remove(stageInfo.stageId)
      statusUpdates.publish1(UpdatedTasks(TaskInfo(stageTaskId(stageInfo), "", "", TaskStatus.Complete, 255.toByte) :: Nil)).unsafeRunAsyncAndForget()
  }

  override def onJobStart(jobStart: SparkListenerJobStart): Unit = jobStart match {
    case SparkListenerJobStart(jobId, time, jobStages, properties) =>
      cleanupStages(jobId)

      jobStageIds.put(jobId, jobStages.map(_.stageId).toSet)

      jobStages.foreach {
        stageInfo =>
          stageInfos.put(stageInfo.stageId, stageInfo)
          statusUpdates.publish1(UpdatedTasks(TaskInfo(stageTaskId(stageInfo), s"Stage ${stageInfo.stageId}", stageInfo.name, TaskStatus.Queued) :: Nil)).unsafeRunAsyncAndForget()
      }
  }

  override def onJobEnd(jobEnd: SparkListenerJobEnd): Unit = cleanupStages(jobEnd.jobId)

  override def onTaskStart(taskStart: SparkListenerTaskStart): Unit = ()
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
