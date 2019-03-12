package polynote.config

import java.io.{File, FileNotFoundException, FileReader}

import cats.effect.IO
import cats.syntax.either._
import cats.syntax.functor._
import io.circe.generic.extras.semiauto._
import io.circe._
import org.log4s.{Logger, getLogger}

final case class Listen(
  port: Int = 8192,
  host: String = "0.0.0.0"
)

object Listen {
  implicit val encoder: ObjectEncoder[Listen] = deriveEncoder
  implicit val decoder: Decoder[Listen] = deriveDecoder
}

final case class PolynoteConfig(
  listen: Listen = Listen(),
  repositories: List[RepositoryConfig] = Nil,
  exclusions: List[String] = Nil,
  dependencies: Map[String, List[String]] = Map.empty,
  spark: Map[String, String] = Map.empty
)


object PolynoteConfig {
  implicit val encoder: ObjectEncoder[PolynoteConfig] = deriveEncoder
  implicit val decoder: Decoder[PolynoteConfig] = deriveDecoder

  private val logger: Logger = getLogger

  def parse(content: String): Either[Throwable, PolynoteConfig] = yaml.parser.parse(content).flatMap(_.as[PolynoteConfig])

  def load(file: File): IO[PolynoteConfig] = {

    IO(new FileReader(file)).flatMap { reader =>
      IO.fromEither(yaml.parser.parse(reader).flatMap(_.as[PolynoteConfig]))
        .guarantee(IO(reader.close()))
    } handleErrorWith {
      case err: MatchError =>
        IO.pure(PolynoteConfig()) // TODO: Handles an upstream issue with circe-yaml, on an empty config file https://github.com/circe/circe-yaml/issues/50
      case err: FileNotFoundException =>
        IO(logger.warn(s"Configuration file $file not found; using default configuration")).as(PolynoteConfig())
      case err: Throwable => IO.raiseError(err)
    }

  }

}