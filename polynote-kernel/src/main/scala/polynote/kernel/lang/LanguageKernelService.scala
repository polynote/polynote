package polynote.kernel.lang

import cats.effect.IO
import polynote.kernel.lang.python.PythonInterpreter
import polynote.kernel.lang.scal.ScalaInterpreter


trait LanguageKernelService {
  def priority: Int
  def languageKernels: Map[String, LanguageInterpreter.Factory[IO]]
}

class CoreLanguages extends LanguageKernelService {
  val priority = 0
  override def languageKernels: Map[String, LanguageInterpreter.Factory[IO]] = Map(
    "scala" -> ScalaInterpreter.factory(),
    "python" -> PythonInterpreter.factory())
}
