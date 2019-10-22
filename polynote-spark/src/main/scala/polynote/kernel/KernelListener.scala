package polynote.kernel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.{IntBinaryOperator, ToIntFunction}

import scala.collection.JavaConverters._
import org.apache.spark.scheduler.{JobFailed, JobSucceeded, SparkListener, SparkListenerJobEnd, SparkListenerJobStart, SparkListenerStageCompleted, SparkListenerStageSubmitted, SparkListenerTaskEnd, SparkListenerTaskStart, StageInfo}
import org.apache.spark.sql.SparkSession
import polynote.kernel.logging.Logging
import zio.blocking.Blocking
import zio.clock.Clock
import zio.{Runtime, UIO, ZIO}

class KernelListener(taskManager: TaskManager.Service, session: SparkSession, runtime: Runtime[Blocking with Clock with Logging]) extends SparkListener {

  private val jobUpdaters = new ConcurrentHashMap[Int, (TaskInfo => TaskInfo) => Unit]()
  private val stageUpdaters = new ConcurrentHashMap[Int, (TaskInfo => TaskInfo) => Unit]()
  private val jobStages   = new ConcurrentHashMap[Int, ConcurrentHashMap[Int, StageInfo]]()
  private val stageJobIds = new ConcurrentHashMap[Int, Integer]()
  private val allStages = new ConcurrentHashMap[Int, StageInfo]()
  private val jobTasksCompleted = new ConcurrentHashMap[Int, AtomicInteger]()
  private val stageTasksCompleted = new ConcurrentHashMap[Int, AtomicInteger]()

  private val countTasks = new ToIntFunction[StageInfo] {
    def applyAsInt(value: StageInfo): Int = value.numTasks
  }

  private val sum = new IntBinaryOperator {
    def applyAsInt(left: Int, right: Int): Int = left + right
  }

  private def jobProgress(jobId: Int): Double = jobStages.get(jobId) match {
    case null => 0.0
    case stages =>
      val totalInOutstandingStages = stages.reduceValuesToInt(Long.MaxValue, countTasks, 0, sum)
      val completedInOutstandingStages = stages.keys.asScala.collect {
        case stageId if stageTasksCompleted.containsKey(stageId) => Option(stageTasksCompleted.get(stageId)).map(_.get).getOrElse(0)
      }.sum

      val numCompleted = Option(jobTasksCompleted.get(jobId)).map(_.get).getOrElse(0)
      numCompleted.toDouble / (numCompleted + totalInOutstandingStages - completedInOutstandingStages)
  }

  private def stageProgress(stageId: Int): Double = allStages.get(stageId) match {
    case null => 0.0
    case stageInfo =>
      val numCompleted = Option(stageTasksCompleted.get(stageId)).map(_.get).getOrElse(0)
      numCompleted.toDouble / stageInfo.numTasks
  }

  private def updateJobProgress(jobId: Int): Unit =
    Option(jobUpdaters.get(jobId)).foreach(_(_.progress(jobProgress(jobId))))

  private def updateStageProgress(stageId: Int): Unit = {
    Option(stageUpdaters.get(stageId)).foreach(_(_.progress(stageProgress(stageId))))
    Option(stageJobIds.get(stageId)).foreach {
      jobId => updateJobProgress(jobId)
    }
  }

  private def cancelJob(jobId: Int): ZIO[Logging, Nothing, Unit] = ZIO.effect {
    session.sparkContext.cancelJob(jobId)
  }.catchAll(Logging.error("Unable to cancel job", _))

  private def cancelStage(stageId: Int): ZIO[Logging, Nothing, Unit] = ZIO.effect {
    session.sparkContext.cancelStage(stageId)
  }.catchAll(Logging.error("Unable to cancel stage", _))

  private def sparkJobTaskId(jobId: Int) = s"SparkJob$jobId"
  private def sparkStageTaskId(stageId: Int) = s"SparkStage$stageId"

  override def onJobStart(jobStart: SparkListenerJobStart): Unit = {
    import jobStart.jobId
    val label = s"Job $jobId"
    val stageInfos = jobStart.stageInfos
    val stageMap = new ConcurrentHashMap[Int, StageInfo]()
    var totalTasks = 0
    stageInfos.foreach {
      si =>
        stageMap.put(si.stageId, si)
        stageJobIds.put(si.stageId, jobId)
        totalTasks += si.numTasks
    }

    jobStages.put(jobId, stageMap)
    jobTasksCompleted.put(jobId, new AtomicInteger(0))
    val jobName = jobStart.stageInfos.headOption.map(_.name).getOrElse("")

    runtime.unsafeRun {
      taskManager.register(sparkJobTaskId(jobId), label, jobName, None, Complete) {
        updater =>
          jobUpdaters.put(jobId, updater)
          cancelJob(jobId)
      }
    }
  }

  override def onJobEnd(jobEnd: SparkListenerJobEnd): Unit = {
    import jobEnd.jobId
    val stages = jobStages.remove(jobId)

    stages.asScala.values.foreach {
      stageInfo =>
        stageJobIds.remove(stageInfo.stageId)
        allStages.remove(stageInfo.stageId)
        stageTasksCompleted.remove(stageInfo.stageId)
        Option(stageUpdaters.get(stageInfo.stageId)).foreach(_(_.completed))
        ()
    }

    jobTasksCompleted.remove(jobId)

    Option(jobUpdaters.remove(jobId)).foreach {
      updater => jobEnd.jobResult match {
        case JobSucceeded => updater(_.completed)
        case _            => updater(_.failed)
      }
    }
  }

  override def onStageSubmitted(stageSubmitted: SparkListenerStageSubmitted): Unit = {
    import stageSubmitted.stageInfo, stageInfo.stageId

    stageTasksCompleted.put(stageId, new AtomicInteger(0))

    if (!allStages.containsKey(stageId)) {
      allStages.put(stageId, stageInfo)
    }

    val maybeJobId = Option(stageJobIds.get(stageId))

    val parent = maybeJobId.map(jobId => sparkJobTaskId(jobId))

    runtime.unsafeRun {
      taskManager.register(sparkStageTaskId(stageId), s"Stage $stageId", stageInfo.name, parent, Complete) {
        updater =>
          stageUpdaters.put(stageId, updater)
          cancelStage(stageId)
      }
    }

    maybeJobId.foreach {
      jobId =>
        jobStages.get(jobId) match {
          case null =>
            val map = new ConcurrentHashMap[Int, StageInfo]()
            map.put(stageId, stageInfo)
            jobStages.put(jobId, map)

          case knownStages if knownStages.containsKey(stageId) =>
            knownStages.size()
          case knownStages =>
            knownStages.put(stageId, stageInfo)
            knownStages.size()
        }
    }
    updateStageProgress(stageId)
  }

  override def onStageCompleted(stageCompleted: SparkListenerStageCompleted): Unit = {
    import stageCompleted.stageInfo, stageInfo.stageId

    Option(stageUpdaters.remove(stageId)).foreach(_(_.completed))
    Option(stageJobIds.remove(stageId)).foreach {
      jobId =>
        Option(jobStages.get(jobId)).foreach(_.remove(stageId))
        updateJobProgress(jobId)
    }

    allStages.remove(stageId)
  }

  override def onTaskEnd(taskEnd: SparkListenerTaskEnd): Unit = {
    import taskEnd.stageId
    val jobId = stageJobIds.get(stageId)
    if (taskEnd.reason == org.apache.spark.Success) {
      val task = stageTasksCompleted.get(stageId) match {
        case null =>
          stageTasksCompleted.putIfAbsent(stageId, new AtomicInteger(0))
          stageTasksCompleted.get(stageId).incrementAndGet()
          jobTasksCompleted.putIfAbsent(jobId, new AtomicInteger(0))
          jobTasksCompleted.get(jobId).incrementAndGet()
          0
        case ai => ai.incrementAndGet()
          jobTasksCompleted.putIfAbsent(jobId, new AtomicInteger(0))
          jobTasksCompleted.get(jobId).incrementAndGet()
      }

      val numTasks = allStages.get(stageId).numTasks
      val increments = numTasks / 256
      if (increments > 0) {
        if (task % increments == 0) {
          updateStageProgress(stageId)
        }
      } else {
        updateStageProgress(stageId)
      }
    }
  }

}
