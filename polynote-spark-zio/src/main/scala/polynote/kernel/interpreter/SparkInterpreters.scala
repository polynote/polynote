package polynote.kernel.interpreter

import polynote.kernel.interpreter.python.PySparkInterpreter
import polynote.kernel.interpreter.scal.ScalaSparkInterpreter
import polynote.kernel.interpreter.sql.SparkSqlInterpreter

class SparkInterpreters extends Loader {
  override def priority: Int = 1
  override def factories: Map[String, Interpreter.Factory] = Map(
    "sql" -> SparkSqlInterpreter.Factory,
    "python" -> PySparkInterpreter.Factory,
    "scala" -> ScalaSparkInterpreter.Factory
  )
}
