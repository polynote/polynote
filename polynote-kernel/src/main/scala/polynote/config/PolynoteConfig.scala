package polynote.config

import java.io.{File, FileNotFoundException, FileReader}
import java.util.UUID

import cats.syntax.either._
import io.circe.generic.extras.semiauto._
import io.circe.syntax._
import io.circe._
import polynote.kernel.TaskB
import polynote.kernel.logging.Logging
import zio.ZIO
import zio.blocking.effectBlocking

final case class Listen(
  port: Int = 8192,
  host: String = "127.0.0.1"
)

object Listen {
  implicit val encoder: ObjectEncoder[Listen] = deriveEncoder
  implicit val decoder: Decoder[Listen] = deriveDecoder
}

final case class Mount(dir: String, mounts: Map[String, Mount] = Map.empty)

object Mount {
  implicit val encoder: ObjectEncoder[Mount] = deriveEncoder
  implicit val decoder: Decoder[Mount] = deriveDecoder[Mount]
}

final case class Storage(cache: String = "tmp", dir: String = "notebooks", mounts: Map[String, Mount] = Map.empty)

object Storage {
  implicit val encoder: ObjectEncoder[Storage] = deriveEncoder
  implicit val decoder: Decoder[Storage] = deriveDecoder[Storage]
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
  kernelIsolation: KernelIsolation = KernelIsolation.Always,
  sharedPackages: List[String] = Nil
) {
  private final val defaultShares = "scala|javax?|jdk|sun|com.sun|com.oracle|polynote|org.w3c|org.xml|org.omg|org.ietf|org.jcp|org.apache.spark|org.spark_project|org.glassfish.jersey|org.jvnet.hk2|org.apache.hadoop|org.codehaus|org.slf4j|org.log4j|org.apache.log4j"

  def getSharedString: String = "^(" + (sharedPackages :+ defaultShares).mkString("|") + ")\\."
}

object Behavior {
  implicit val encoder: ObjectEncoder[Behavior] = deriveEncoder
  implicit val decoder: Decoder[Behavior] = deriveDecoder
}

final case class AuthProvider(provider: String, config: JsonObject)

object AuthProvider {
  implicit val encoder: ObjectEncoder[AuthProvider] = deriveEncoder
  implicit val decoder: Decoder[AuthProvider] = deriveDecoder
}

final case class Security(
  websocketKey: Option[String] = None,
  auth: Option[AuthProvider] = None
)

object Security {
  implicit val encoder: ObjectEncoder[Security] = deriveEncoder
  implicit val decoder: Decoder[Security] = deriveDecoder
}

final case class UI(
  baseUri: String = "/"
)

object UI {
  implicit val encoder: ObjectEncoder[UI] = deriveEncoder
  implicit val decoder: Decoder[UI] = deriveDecoder
}

case class Credentials(
  coursier: Option[Credentials.Coursier] = None
)
object Credentials {
  final case class Coursier(path: String)
  object Coursier {
    implicit val encoder: ObjectEncoder[Coursier] = deriveEncoder
    implicit val decoder: Decoder[Coursier] = deriveDecoder
  }

  implicit val encoder: ObjectEncoder[Credentials] = deriveEncoder
  implicit val decoder: Decoder[Credentials] = deriveDecoder
}

final case class PolynoteConfig(
  listen: Listen = Listen(),
  storage: Storage = Storage(),
  repositories: List[RepositoryConfig] = Nil,
  exclusions: List[String] = Nil,
  dependencies: Map[String, List[String]] = Map.empty,
  spark: Map[String, String] = Map.empty,
  behavior: Behavior = Behavior(),
  security: Security = Security(),
  ui: UI = UI(),
  credentials: Credentials = Credentials()
)


object PolynoteConfig {
  implicit val encoder: ObjectEncoder[PolynoteConfig] = deriveEncoder
  implicit val decoder: Decoder[PolynoteConfig] = deriveDecoder[PolynoteConfig]

  private val defaultConfig = "default.yml" // we expect this to be in the directory Polynote was launched from.

  def parse(content: String): Either[Throwable, PolynoteConfig] = yaml.parser.parse(content).flatMap(_.as[PolynoteConfig])

  private def parseFile(file: File): TaskB[Json] =
    effectBlocking(file.exists()).flatMap {
      case true => effectBlocking(new FileReader(file)).bracketAuto {
        reader => ZIO.fromEither(yaml.parser.parse(reader)).map {
          json =>
            // if the config is empty, its Json value is "false"... (reliable sources indicate this is part of the yaml spec)
            if (json.isBoolean) Json.fromJsonObject(JsonObject.empty) else json
        }
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
