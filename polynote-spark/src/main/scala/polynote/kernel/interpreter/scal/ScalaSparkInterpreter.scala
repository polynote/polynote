package polynote.kernel.interpreter.scal

import polynote.kernel.environment.CurrentNotebook
import polynote.kernel.{BaseEnv, CellEnv, GlobalEnv, InterpreterEnv, ScalaCompiler, TaskManager}
import polynote.kernel.interpreter.{Interpreter, State}
import zio.blocking.Blocking
import zio.{RIO, ZIO}

class ScalaSparkInterpreter private[scal] (
  compiler: ScalaCompiler,
  indexer: ClassIndexer
) extends ScalaInterpreter(compiler, indexer) {
  import scalaCompiler.global._
  override protected def transformCode(code: List[Tree]): List[Tree] =
    q"org.apache.spark.sql.catalyst.encoders.OuterScopes.addOuterScope(this)" :: super.transformCode(code)

  override def init(state: State): RIO[InterpreterEnv, State] = super.init(state).flatMap {
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

  def apply(): RIO[ScalaCompiler.Provider with Blocking, ScalaSparkInterpreter] = for {
    compiler <- ZIO.access[ScalaCompiler.Provider](_.scalaCompiler)
    index    <- ClassIndexer.default
  } yield new ScalaSparkInterpreter(compiler, index)

  object Factory extends ScalaInterpreter.Factory {
    override def apply(): RIO[ScalaCompiler.Provider with Blocking, ScalaSparkInterpreter] = {
      val res = ScalaSparkInterpreter()
      res
    }
    override val requireSpark: Boolean = true
    override val priority: Int = 1
  }

}
