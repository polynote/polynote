package polynote.kernel.lang
import cats.effect.IO
import polynote.kernel.lang.scal.ScalaSparkInterpreter
import polynote.kernel.lang.sql.SparkSqlInterpreter

class SparkLanguages extends LanguageInterpreterService {
  def priority: Int = 1
  def interpreters: Map[String, LanguageInterpreter.Factory[IO]] = Map(
    "scala" -> ScalaSparkInterpreter.factory(),
    "sql" -> SparkSqlInterpreter.factory()
  )
}
