package polynote.kernel.lang
import cats.effect.IO
import polynote.kernel.lang.scal.ScalaSparkInterpreter
import polynote.kernel.lang.sql.SparkSqlInterpreter

class SparkLanguages extends LanguageKernelService {
  def priority: Int = 1
  def languageKernels: Map[String, LanguageKernel.Factory[IO]] = Map(
    "scala" -> ScalaSparkInterpreter.factory(),
    "sql" -> SparkSqlInterpreter.factory()
  )
}
