package polynote.kernel.interpreter.scal

import polynote.kernel.environment.CurrentNotebook
import polynote.kernel.{BaseEnv, CellEnv, GlobalEnv, InterpreterEnv, ScalaCompiler, TaskManager}
import polynote.kernel.interpreter.{Interpreter, State}
import zio.{TaskR, ZIO}

class ScalaSparkInterpreter private[scal] (
  compiler: ScalaCompiler
) extends ScalaInterpreter(compiler) {
  import scalaCompiler.global._
  override protected def transformCode(code: List[Tree]): List[Tree] =
    q"org.apache.spark.sql.catalyst.encoders.OuterScopes.addOuterScope(this)" :: super.transformCode(code)

  override def init(state: State): TaskR[InterpreterEnv, State] = super.init(state).flatMap {
    parentState => run(ScalaSparkInterpreter.sparkPredef, State.predef(parentState, parentState))
  }
}

object ScalaSparkInterpreter {

  private val sparkPredef =
    s"""import org.apache.spark.sql.SparkSession
       |@transient val spark: SparkSession = if (org.apache.spark.repl.Main.sparkSession != null) {
       |            org.apache.spark.repl.Main.sparkSession
       |          } else {
       |            org.apache.spark.repl.Main.createSparkSession()
       |          }
       |import org.apache.spark.sql.{DataFrame, Dataset}
       |import spark.implicits._
       |import org.apache.spark.sql.functions._
       |""".stripMargin

  def apply(): TaskR[ScalaCompiler.Provider, ScalaSparkInterpreter] = ZIO.access[ScalaCompiler.Provider](_.scalaCompiler).map {
    compiler => new ScalaSparkInterpreter(compiler)
  }

  object Factory extends ScalaInterpreter.Factory {
    override def apply(): TaskR[ScalaCompiler.Provider, ScalaSparkInterpreter] = {
      val res = ScalaSparkInterpreter()
      res
    }
    override val requireSpark: Boolean = true
    override val priority: Int = 1
  }

}
