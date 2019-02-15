package polynote.kernel.lang
import cats.effect.IO
import polynote.kernel.context.GlobalInfo
import polynote.kernel.lang.scal.ScalaSparkInterpreter

class SparkLanguages extends LanguageKernelService {
  def priority: Int = 1
  def languageKernels: Map[String, LanguageKernel.Factory[IO, GlobalInfo]] = Map(
    "scala" -> ScalaSparkInterpreter.factory())
}
