package org.apache.spark.sql

import org.apache.spark.sql.catalyst.parser.ParserInterface

package object polynote {

  def getParser(spark: SparkSession): ParserInterface = spark.sessionState.sqlParser

}
