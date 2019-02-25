package polynote.kernel.lang

import cats.effect.IO
import polynote.kernel.lang.python.PythonInterpreter
import polynote.kernel.lang.scal.ScalaInterpreter


trait LanguageInterpreterService {
  def priority: Int
  def interpreters: Map[String, LanguageInterpreter.Factory[IO]]
}

class CoreLanguages extends LanguageInterpreterService {
  val priority = 0
  override def interpreters: Map[String, LanguageInterpreter.Factory[IO]] = Map(
    "scala" -> ScalaInterpreter.factory(),
    "python" -> PythonInterpreter.factory())
}
