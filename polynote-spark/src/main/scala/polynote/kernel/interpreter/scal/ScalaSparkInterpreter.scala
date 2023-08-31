package polynote.kernel.interpreter.scal

import polynote.kernel.environment.{Config, CurrentNotebook, CurrentTask}
import polynote.kernel.{BaseEnv, CellEnv, GlobalEnv, InterpreterEnv, ScalaCompiler}
import polynote.kernel.interpreter.{Interpreter, State}
import polynote.kernel.task.TaskManager
import zio.blocking.Blocking
import zio.clock.Clock
import zio.{RIO, ZIO}

class ScalaSparkInterpreter private[scal] (
  compiler: ScalaCompiler,
  indexer: ClassIndexer,
  scan: Option[SemanticDbScan]
) extends ScalaInterpreter(compiler, indexer, scan) {
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

  def apply(): RIO[ScalaCompiler.Provider with BaseEnv with Config with TaskManager, ScalaSparkInterpreter] = for {
    compiler <- ScalaCompiler.access
    index    <- ClassIndexer.default
    scan     <- ScalaInterpreter.maybeScan(compiler)
  } yield new ScalaSparkInterpreter(compiler, index, scan)

  object Factory extends ScalaInterpreter.Factory {
    override def apply(): RIO[BaseEnv with GlobalEnv with ScalaCompiler.Provider with CurrentNotebook with CurrentTask with TaskManager, ScalaSparkInterpreter] = ScalaSparkInterpreter()
    override val requireSpark: Boolean = true
    override val priority: Int = 1
  }

}
