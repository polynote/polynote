package polynote.config

import java.io.{File, FileNotFoundException, FileReader}

import cats.syntax.either._
import io.circe.generic.extras.semiauto._
import io.circe._
import polynote.kernel.TaskB
import polynote.kernel.logging.Logging
import zio.ZIO
import zio.blocking.effectBlocking

import scala.collection.parallel.Task

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
  kernelIsolation: KernelIsolation = KernelIsolation.SparkOnly
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

  def parse(content: String): Either[Throwable, PolynoteConfig] = yaml.parser.parse(content).flatMap(_.as[PolynoteConfig])

  private def parseFile(file: File): TaskB[Json] =
    effectBlocking(file.exists()).flatMap {
      case true => effectBlocking(new FileReader(file)).bracketAuto {
        reader => ZIO.fromEither(yaml.parser.parse(reader))
      }
      case false => ZIO.succeed(Json.fromJsonObject(JsonObject.empty))
    }

  def load(file: File): TaskB[PolynoteConfig] = {

    val parsed  = parseFile(file)
    val default = parseFile(new File(defaultConfig)).catchAll {
      err =>
        Logging.error(s"Unable to parse default config file $defaultConfig", err)
          .as(Json.fromJsonObject(JsonObject.empty))
    }

    val configIO = for {
      configJson  <- parsed
      defaultJson <- default
      merged = defaultJson.deepMerge(configJson) // priority goes to configJson
      parsedConfig <- ZIO.fromEither(merged.as[PolynoteConfig])
    } yield parsedConfig

    configIO
      .catchAll {
        case _: MatchError =>
          ZIO.succeed(PolynoteConfig()) // TODO: Handles an upstream issue with circe-yaml, on an empty config file https://github.com/circe/circe-yaml/issues/50
        case e: FileNotFoundException =>
          Logging.error(s"Configuration file $file not found; using default configuration", e).as(PolynoteConfig())
        case err: Throwable => ZIO.fail(err)
      }
  }

}