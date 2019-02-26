package polynote.server

import java.io.{File, FileNotFoundException, FileReader}

import cats.effect.IO
import cats.syntax.either._
import cats.syntax.functor._
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.encoding.ConfiguredObjectEncoder
import io.circe.generic.extras.semiauto._
import io.circe.{Decoder, ObjectEncoder}
import io.circe.yaml
import org.log4s.{Logger, getLogger}
import polynote.config.{DependencyConfigs, RepositoryConfig}
import polynote.messages.TinyMap

final case class Listen(
  port: Int = 8192,
  address: String = "0.0.0.0"
)

object Listen {
  implicit val encoder: ObjectEncoder[Listen] = deriveEncoder
  implicit val decoder: Decoder[Listen] = deriveDecoder
}

final case class ServerConfig(
  listen: Listen = Listen(),
  repositories: List[RepositoryConfig] = Nil,
  dependencies: Map[String, List[String]] = Map.empty
)


object ServerConfig {
  implicit val encoder: ObjectEncoder[ServerConfig] = deriveEncoder
  implicit val decoder: Decoder[ServerConfig] = deriveDecoder

  private val logger: Logger = getLogger

  def parse(content: String): Either[Throwable, ServerConfig] = yaml.parser.parse(content).flatMap(_.as[ServerConfig])

  def load(file: File): IO[ServerConfig] = IO(new FileReader(file)).bracket {
    reader => IO.fromEither(yaml.parser.parse(reader).flatMap(_.as[ServerConfig]))
  }(reader => IO(reader.close())).handleErrorWith {
    case err: FileNotFoundException =>
      IO(logger.warn(s"Configuration file $file not found; using default configuration")).as(new ServerConfig())
    case err: Throwable => IO.raiseError(err)
  }

}