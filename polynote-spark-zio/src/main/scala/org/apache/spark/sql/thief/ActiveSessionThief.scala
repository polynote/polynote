package org.apache.spark.sql.thief

import org.apache.spark.sql.SparkSession

object ActiveSessionThief {
  def apply(): Option[SparkSession] = SparkSession.getActiveSession
}
