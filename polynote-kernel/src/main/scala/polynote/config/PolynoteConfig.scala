package polynote.config

import java.io.{File, FileNotFoundException, FileReader}

import cats.effect.{IO, Sync}
import cats.syntax.either._
import cats.syntax.functor._
import io.circe.generic.extras.semiauto._
import io.circe._
import polynote.config.KernelIsolation.SparkOnly

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

sealed trait KernelIsolation
object KernelIsolation {
  case object Never extends KernelIsolation
  case object Always extends KernelIsolation
  case object SparkOnly extends KernelIsolation

  implicit val encoder: Encoder[KernelIsolation] = Encoder.instance {
    case Never     => Json.fromString("never")
    case Always    => Json.fromString("always")
    case SparkOnly => Json.fromString("spark")
  }

  implicit val decoder: Decoder[KernelIsolation] = Decoder.decodeString.emap {
    case "never"  => Right(Never)
    case "always" => Right(Always)
    case "spark"  => Right(SparkOnly)
    case other    => Left(s"Invalid value for kernel_isolation: $other (expected one of: never, always, spark)")
  }
}

final case class Behavior(
  dependencyIsolation: Boolean = true,
  kernelIsolation: KernelIsolation = SparkOnly
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

  def load[F[_]](file: File)(implicit F: Sync[F]): F[PolynoteConfig] = {
    import cats.syntax.flatMap._
    import cats.syntax.applicativeError._
    val configJsonIO = if (file.exists()) {
      F.bracket(F.delay(new FileReader(file))) {
        configReader =>
          F.fromEither(yaml.parser.parse(configReader))
      }(reader => F.delay(reader.close()))
    } else F.pure(Json.fromJsonObject(JsonObject.empty))

    val defaultFile = new File(defaultConfig)
    logger.debug(s"Loading default config file from: ${defaultFile.getAbsolutePath}")

    val defaultJsonIO =
      F.bracket(F.delay(new FileReader(defaultFile))) {
        defaultReader =>
          F.fromEither(yaml.parser.parse(defaultReader))
      }(reader => F.delay(reader.close())).handleErrorWith(_ => F.pure(Json.fromJsonObject(JsonObject.empty)))

    val configIO = for {
      configJson <- configJsonIO
      defaultJson <- defaultJsonIO
      merged = defaultJson.deepMerge(configJson) // priority goes to configJson
      parsedConfig <- F.fromEither(merged.as[PolynoteConfig])
    } yield parsedConfig

    configIO
      .handleErrorWith {
        case _: MatchError =>
          F.pure(PolynoteConfig()) // TODO: Handles an upstream issue with circe-yaml, on an empty config file https://github.com/circe/circe-yaml/issues/50
        case e: FileNotFoundException =>
          F.delay(logger.error(e)(s"Configuration file $file not found; using default configuration")).as(PolynoteConfig())
        case err: Throwable => F.raiseError(err)
      }
  }

}