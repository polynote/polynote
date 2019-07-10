package polynote.config

import java.io.{File, FileNotFoundException, FileReader}

import cats.effect.IO
import cats.syntax.either._
import cats.syntax.functor._
import io.circe.generic.extras.semiauto._
import io.circe._

final case class Listen(
  port: Int = 8192,
  host: String = "0.0.0.0"
)

object Listen {
  implicit val encoder: ObjectEncoder[Listen] = deriveEncoder
  implicit val decoder: Decoder[Listen] = deriveDecoder
}

final case class Storage(dir: String = "notebooks")

object Storage {
  implicit val encoder: ObjectEncoder[Storage] = deriveEncoder
  implicit val decoder: Decoder[Storage] = deriveDecoder
}

final case class PolynoteConfig(
  listen: Listen = Listen(),
  storage: Storage = Storage(),
  repositories: List[RepositoryConfig] = Nil,
  exclusions: List[String] = Nil,
  dependencies: Map[String, List[String]] = Map.empty,
  spark: Map[String, String] = Map.empty
)


object PolynoteConfig {
  implicit val encoder: ObjectEncoder[PolynoteConfig] = deriveEncoder
  implicit val decoder: Decoder[PolynoteConfig] = deriveDecoder[PolynoteConfig].prepare {
    cursor =>
      cursor.downField("base").focus.flatMap {
        baseFileName =>
          baseFileName.asString.map {
            f =>
              // parse base config to JSON. Note that this means that we only support one level of base configs (e.g., the base can't have its own base).
              // this is so we don't get into an infinite loop situation where two configs depend on each other.
              val baseConfigJson = yaml.parser.parse(new FileReader(f)).right.get
              cursor.withFocus {
                json =>
                  baseConfigJson.deepMerge(json) // merge base with precedence going to current Json.
              }
          }
      }.getOrElse(cursor)
  }

  private val logger = new PolyLogger

  def parse(content: String): Either[Throwable, PolynoteConfig] = yaml.parser.parse(content).flatMap(_.as[PolynoteConfig])

  def load(file: File): IO[PolynoteConfig] = {

    IO(new FileReader(file)).flatMap { reader =>
      IO.fromEither(yaml.parser.parse(reader).flatMap(_.as[PolynoteConfig]))
        .guarantee(IO(reader.close()))
    } handleErrorWith {
      case _: MatchError =>
        IO.pure(PolynoteConfig()) // TODO: Handles an upstream issue with circe-yaml, on an empty config file https://github.com/circe/circe-yaml/issues/50
      case _: FileNotFoundException =>
        IO(logger.error(s"Configuration file $file not found; using default configuration")).as(PolynoteConfig())
      case err: Throwable => IO.raiseError(err)
    }

  }

}