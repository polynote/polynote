package polynote.kernel.lang

import cats.effect.IO
import polynote.kernel.context.GlobalInfo
import polynote.kernel.lang.python.PythonInterpreter
import polynote.kernel.lang.scal.ScalaInterpreter


trait LanguageKernelService {
  def priority: Int
  def languageKernels: Map[String, LanguageKernel.Factory[IO, GlobalInfo]]
}

class CoreLanguages extends LanguageKernelService {
  val priority = 0
  override def languageKernels: Map[String, LanguageKernel.Factory[IO, GlobalInfo]] = Map(
    "scala" -> ScalaInterpreter.factory(),
    "python" -> PythonInterpreter.factory())
}
