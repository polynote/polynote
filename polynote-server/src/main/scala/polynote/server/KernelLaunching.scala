package polynote.server

import java.util.ServiceLoader

import cats.effect.{ContextShift, IO}
import polynote.config.PolynoteConfig
import polynote.kernel.dependency.CoursierFetcher
import polynote.kernel.lang.{LanguageInterpreter, LanguageInterpreterService}

import scala.collection.JavaConverters._

trait KernelLaunching {

  protected implicit def contextShift: ContextShift[IO]

  protected val interpreters: Map[String, LanguageInterpreter.Factory[IO]] =
    ServiceLoader.load(classOf[LanguageInterpreterService]).iterator.asScala.toSeq
      .sortBy(_.priority)
      .foldLeft(Map.empty[String, LanguageInterpreter.Factory[IO]]) {
        (accum, next) => accum ++ next.interpreters
      }


  protected val dependencyFetcher = new CoursierFetcher()

  protected def kernelFactory(config: PolynoteConfig): KernelFactory[IO] = new IOKernelFactory(Map("scala" -> dependencyFetcher), interpreters, config)

}
