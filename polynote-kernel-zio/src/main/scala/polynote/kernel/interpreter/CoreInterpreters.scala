package polynote.kernel.interpreter

import polynote.kernel.interpreter.scal.ScalaInterpreter

class CoreInterpreters extends Loader {
  override def priority: Int = 0
  override def factories: Map[String, Interpreter.Factory] = Map(
    "scala" -> ScalaInterpreter.Factory
  )
}
