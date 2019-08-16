package polynote.config

import java.io.{File, FileNotFoundException, FileReader}

import cats.effect.IO
import cats.syntax.either._
import cats.syntax.functor._
import io.circe.generic.extras.semiauto._
import io.circe._

final case class Listen(
  port: Int = 8192,
  host: String = "127.0.0.1"
)

object Listen {
  implicit val encoder: ObjectEncoder[Listen] = deriveEncoder
  implicit val decoder: Decoder[Listen] = deriveDecoder
}

final case class Storage(dir: String = "notebooks", cache: String = "tmp")

object Storage {
  implicit val encoder: ObjectEncoder[Storage] = deriveEncoder
  implicit val decoder: Decoder[Storage] = deriveDecoder
}

final case class Behavior(
  dependencyIsolation: Boolean = true
)

object Behavior {
  implicit val encoder: ObjectEncoder[Behavior] = deriveEncoder
  implicit val decoder: Decoder[Behavior] = deriveDecoder
}

final case class PolynoteConfig(
  listen: Listen = Listen(),
  storage: Storage = Storage(),
  repositories: List[RepositoryConfig] = Nil,
  exclusions: List[String] = Nil,
  dependencies: Map[String, List[String]] = Map.empty,
  spark: Map[String, String] = Map.empty,
  behavior: Behavior = Behavior()
)


object PolynoteConfig {
  implicit val encoder: ObjectEncoder[PolynoteConfig] = deriveEncoder
  implicit val decoder: Decoder[PolynoteConfig] = deriveDecoder[PolynoteConfig]

  private val defaultConfig = "default.yml" // we expect this to be in the directory Polynote was launched from.

  private val logger = new PolyLogger

  def parse(content: String): Either[Throwable, PolynoteConfig] = yaml.parser.parse(content).flatMap(_.as[PolynoteConfig])

  def load(file: File): IO[PolynoteConfig] = {

    val configJsonIO = if (file.exists()) {
      IO(new FileReader(file)).flatMap {
        configReader =>
          IO.fromEither(yaml.parser.parse(configReader)).guarantee(IO(configReader.close()))
      }
    } else IO.pure(Json.fromJsonObject(JsonObject.empty))

    val defaultJsonIO = IO(new FileReader(new File(defaultConfig))).flatMap {
      defaultReader =>
        IO.fromEither(yaml.parser.parse(defaultReader)).guarantee(IO(defaultReader.close()))
    }.handleErrorWith(_ => IO.pure(Json.fromJsonObject(JsonObject.empty)))

    val configIO = for {
      configJson <- configJsonIO
      defaultJson <- defaultJsonIO
      merged = defaultJson.deepMerge(configJson) // priority goes to configJson
      parsedConfig <- IO.fromEither(merged.as[PolynoteConfig])
    } yield parsedConfig

    configIO
      .handleErrorWith {
        case _: MatchError =>
          IO.pure(PolynoteConfig()) // TODO: Handles an upstream issue with circe-yaml, on an empty config file https://github.com/circe/circe-yaml/issues/50
        case e: FileNotFoundException =>
          IO(logger.error(e)(s"Configuration file $file not found; using default configuration")).as(PolynoteConfig())
        case err: Throwable => IO.raiseError(err)
      }
  }

}