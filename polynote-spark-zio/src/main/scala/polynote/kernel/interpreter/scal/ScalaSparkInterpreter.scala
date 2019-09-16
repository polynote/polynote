package polynote.kernel.interpreter.scal

import polynote.kernel.environment.CurrentNotebook
import polynote.kernel.{BaseEnv, GlobalEnv, ScalaCompiler, TaskManager}
import polynote.kernel.interpreter.Interpreter
import zio.{TaskR, ZIO}

class ScalaSparkInterpreter private[scal] (
  compiler: ScalaCompiler
) extends ScalaInterpreter(compiler) {
  import scalaCompiler.global._
  override protected def transformCode(code: List[Tree]): List[Tree] =
    q"org.apache.spark.sql.catalyst.encoders.OuterScopes.addOuterScope(this)" :: super.transformCode(code)

}

object ScalaSparkInterpreter {

  def apply(): TaskR[ScalaCompiler.Provider, ScalaSparkInterpreter] = ZIO.access[ScalaCompiler.Provider](_.scalaCompiler).map {
    compiler => new ScalaSparkInterpreter(compiler)
  }

  object Factory extends ScalaInterpreter.Factory {
    override def apply(): TaskR[ScalaCompiler.Provider, ScalaSparkInterpreter] = {
      val res = ScalaSparkInterpreter()
      res
    }
  }

}
