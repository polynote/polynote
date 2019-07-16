package polynote.kernel.lang
import cats.effect.IO
import polynote.kernel.dependency.{CoursierFetcher, DependencyManagerFactory}
import polynote.kernel.lang.python.{PySparkInterpreter, VirtualEnvManager}
import polynote.kernel.lang.scal.ScalaSparkInterpreter
import polynote.kernel.lang.sql.SparkSqlInterpreter

class SparkLanguages extends LanguageInterpreterService {
  def priority: Int = 1
  def interpreters: Map[String, LanguageInterpreter.Factory[IO]] = Map(
    "scala" -> ScalaSparkInterpreter.factory(),
    "sql" -> SparkSqlInterpreter.factory(),
    "python" -> PySparkInterpreter.factory()
  )
  def dependencyManagers: Map[String, DependencyManagerFactory[IO]] = Map(
    "scala" -> CoursierFetcher.Factory,
    "sql" -> CoursierFetcher.Factory,
    "python" -> VirtualEnvManager.Factory)
}
