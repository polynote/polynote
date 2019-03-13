package org.apache.spark.sql.thief

import org.apache.spark.scheduler.DAGScheduler
import org.apache.spark.sql.SparkSession

object DAGSchedulerThief {
  def apply(sparkSession: SparkSession): DAGScheduler = sparkSession.sparkContext.dagScheduler
}
