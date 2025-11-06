package polynote.runtime.spark.compat

import org.apache.spark.sql.Row
import org.apache.spark.sql.catalyst.encoders.{ExpressionEncoder, RowEncoder}
import org.apache.spark.sql.types.StructType

object SparkVersionCompat {
  def rowEncoder(schema: StructType): ExpressionEncoder[Row] = {
    RowEncoder(schema)
  }
}
