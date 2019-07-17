package polynote.kernel.lang

import cats.effect.IO
import polynote.kernel.dependency.{CoursierFetcher, DependencyManagerFactory, DependencyProvider}
import polynote.kernel.lang.python.{PythonInterpreter, VirtualEnvManager}
import polynote.kernel.lang.scal.ScalaInterpreter


trait LanguageInterpreterService {
  def priority: Int
  def interpreters: Map[String, LanguageInterpreter.Factory[IO]]
  def dependencyManagers: Map[String, DependencyManagerFactory[IO]]
}

class CoreLanguages extends LanguageInterpreterService {
  val priority = 0
  override def interpreters: Map[String, LanguageInterpreter.Factory[IO]] = Map(
    "scala" -> ScalaInterpreter.factory(),
    "python" -> PythonInterpreter.factory())

  override def dependencyManagers: Map[String, DependencyManagerFactory[IO]] = Map(
    "scala" -> CoursierFetcher.Factory,
    "python" -> VirtualEnvManager.Factory)
}
