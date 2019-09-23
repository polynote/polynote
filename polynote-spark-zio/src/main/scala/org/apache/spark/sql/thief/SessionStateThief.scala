package org.apache.spark.sql.thief

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.internal.SessionState

object SessionStateThief {
  def apply(session: SparkSession): SessionState = session.sessionState
}
