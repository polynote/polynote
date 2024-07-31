package polynote.config

import java.io.{File, FileNotFoundException, FileReader}
import java.net.{InetSocketAddress, URI}
import java.nio.file.Path
import java.util.UUID
import java.util.regex.Pattern
import cats.syntax.either._
import io.circe.generic.extras.semiauto.{deriveConfiguredEncoder, deriveConfiguredDecoder}
import io.circe.syntax._
import io.circe._
import polynote.kernel.{BaseEnv, TaskB}
import polynote.kernel.environment.Config
import polynote.kernel.logging.Logging
import polynote.messages.{ShortMap, ShortString, shortMapEncoder}
import scodec.{Attempt, Codec}
import scodec.codecs.implicits._
import scodec.codecs.utf8_32
import zio.{ZIO, ZLayer}
import zio.blocking.{Blocking, effectBlocking}
import shapeless.cachedImplicit

import scala.util.Try

final case class Listen(
  port: Int = 8192,
  host: String = "127.0.0.1"
) {
  lazy val toSocketAddress: InetSocketAddress = new InetSocketAddress(host, port)

}

object Listen {
  implicit val encoder: Encoder.AsObject[Listen] = deriveConfiguredEncoder
  implicit val decoder: Decoder[Listen] = deriveConfiguredDecoder
}

final case class Mount(dir: String, mounts: Map[String, Mount] = Map.empty)

object Mount {
  implicit val encoder: Encoder.AsObject[Mount] = deriveConfiguredEncoder
  implicit val decoder: Decoder[Mount] = deriveConfiguredDecoder[Mount]
}

final case class KernelConfig(
  listen: Option[String] = None,
  portRange: Option[Range] = None,
  scalaVersion: Option[String] = None,
  jvmArgs: Option[Seq[String]] = None
)

object KernelConfig {
  private implicit val rangeDecoder: Decoder[Range] = Decoder.decodeString.emap {
    str => str.split(':') match {
      case Array(from, to) => Either.catchNonFatal(Range.inclusive(from.toInt, to.toInt)).leftMap(_.getMessage)
      case _               => Left(s"Invalid range $str (must be e.g. 1234:4321)")
    }
  }

  private implicit val rangeEncoder: Encoder[Range] = Encoder.encodeString.contramap[Range] { portRange =>
    portRange.start + ":" + portRange.end
  }

  implicit val encoder: Encoder.AsObject[KernelConfig] = deriveConfiguredEncoder
  implicit val decoder: Decoder[KernelConfig] = deriveConfiguredDecoder[KernelConfig]
}

final case class Wal(
  enable: Boolean = false
)

object Wal {
  implicit val encoder: Encoder.AsObject[Wal] = deriveConfiguredEncoder
  implicit val decoder: Decoder[Wal] = deriveConfiguredDecoder[Wal]
}

final case class Storage(
  cache: String = "tmp",
  dir: String = "notebooks",
  mounts: Map[String, Mount] = Map.empty,
  wal: Wal = Wal()
)

object Storage {
  implicit val encoder: Encoder.AsObject[Storage] = deriveConfiguredEncoder
  implicit val decoder: Decoder[Storage] = deriveConfiguredDecoder[Storage]
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
  kernelIsolation: KernelIsolation = KernelIsolation.Always, // TODO: Should move this to KernelConfig now?
  sharedPackages: List[String] = Nil,
  notebookTemplates: List[ShortString] = Nil
) {
  private final val defaultShares = "scala|javax?|jdk|sun|com.sun|com.oracle|polynote|org.w3c|org.xml|org.omg|org.ietf|org.jcp|org.apache.spark|org.spark_project|org.glassfish.jersey|org.jvnet.hk2|org.apache.hadoop|org.codehaus|org.slf4j|org.log4j|org.apache.log4j"

  def getSharedString: String = "^(" + (sharedPackages :+ defaultShares).mkString("|") + ")\\."
}

object Behavior {
  implicit val encoder: Encoder.AsObject[Behavior] = deriveConfiguredEncoder
  implicit val decoder: Decoder[Behavior] = deriveConfiguredDecoder
}

final case class AuthProvider(provider: String, config: JsonObject)

object AuthProvider {
  implicit val encoder: Encoder.AsObject[AuthProvider] = deriveConfiguredEncoder
  implicit val decoder: Decoder[AuthProvider] = deriveConfiguredDecoder
}

final case class Security(
  websocketKey: Option[String] = None,
  auth: Option[AuthProvider] = None
)

object Security {
  implicit val encoder: Encoder.AsObject[Security] = deriveConfiguredEncoder
  implicit val decoder: Decoder[Security] = deriveConfiguredDecoder
}

final case class UI(
  baseUri: String = "/"
)

object UI {
  implicit val encoder: Encoder.AsObject[UI] = deriveConfiguredEncoder
  implicit val decoder: Decoder[UI] = deriveConfiguredDecoder
}

case class Credentials(
  coursier: Option[Credentials.Coursier] = None
)
object Credentials {
  final case class Coursier(path: String)
  object Coursier {
    implicit val encoder: Encoder.AsObject[Coursier] = deriveConfiguredEncoder
    implicit val decoder: Decoder[Coursier] = deriveConfiguredDecoder
  }

  implicit val encoder: Encoder.AsObject[Credentials] = deriveConfiguredEncoder
  implicit val decoder: Decoder[Credentials] = deriveConfiguredDecoder
}

final case class ScalaVersionConfig(
  versionNumber: String,
  versionProperties: ShortMap[String, String] = ShortMap(Map.empty[String, String]),
  sparkSubmitArgs: Option[String] = None
)

object ScalaVersionConfig {
  implicit val encoder: Encoder.AsObject[ScalaVersionConfig] = deriveConfiguredEncoder
  implicit val decoder: Decoder[ScalaVersionConfig] = deriveConfiguredDecoder
}

final case class SparkPropertySet(
  name: String,
  properties: ShortMap[String, String] = ShortMap(Map.empty[String, String]),
  sparkSubmitArgs: Option[String] = None,
  versionConfigs: Option[List[ScalaVersionConfig]] = None,
  distClasspathFilter: Option[Pattern] = None
)

object SparkPropertySet {
  implicit val decoder: Decoder[SparkPropertySet] = deriveConfiguredDecoder
  implicit val encoder: Encoder[SparkPropertySet] = deriveConfiguredEncoder
  private implicit val patternCodec: Codec[Pattern] = utf8_32.exmap(str => Attempt.fromTry(Try(Pattern.compile(str))), pat => Attempt.fromTry(Try(pat.pattern())))
  implicit val codec: Codec[SparkPropertySet] = cachedImplicit[Codec[SparkPropertySet]]
}

final case class PySparkConfig(
  distributeDependencies: Option[Boolean] = None,
  distributionExcludes: List[String] = Nil
)

object PySparkConfig {
  implicit val encoder: Encoder.AsObject[PySparkConfig] = deriveConfiguredEncoder
  implicit val decoder: Decoder[PySparkConfig] = deriveConfiguredDecoder
}

final case class SparkConfig(
  properties: Map[String, String] = Map.empty,
  sparkSubmitArgs: Option[String] = None,
  distClasspathFilter: Option[Pattern] = None,
  propertySets: Option[List[SparkPropertySet]] = None,
  defaultPropertySet: Option[String] = None,
  pyspark: Option[PySparkConfig] = None
)

object SparkConfig {
  def fromMap(properties: Map[String, String]): SparkConfig = SparkConfig(
    properties - "sparkSubmitArgs",
    properties.get("sparkSubmitArgs"),
    None,
    None,
    None
  )

  // TODO: remove once NotebookConfig no longer uses the Map with magic sparkSubmitArgs field
  def toMap(config: SparkConfig): Map[String, String] = config.properties ++ config.sparkSubmitArgs.toList.map("sparkSubmitArgs" -> _).toMap

  private val legacyDecoder: Decoder[SparkConfig] = mapStringStringDecoder.map(fromMap)
  private val newDecoder: Decoder[SparkConfig] = deriveConfiguredDecoder
  implicit val decoder: Decoder[SparkConfig] = Decoder.decodeJsonObject.flatMap {
    case obj if obj.contains("properties") || obj.contains("spark_submit_args") || obj.contains("dist_classpath_filter") || obj.contains("property_sets") => newDecoder
    case _ => legacyDecoder
  }
  implicit val encoder: Encoder[SparkConfig] = deriveConfiguredEncoder
}

final case class StaticConfig(
  path: Option[Path] = None,
  url: Option[URI] = None
)

object StaticConfig {
  implicit val encoder: Encoder[StaticConfig] = deriveConfiguredEncoder
  implicit val decoder: Decoder[StaticConfig] = deriveConfiguredDecoder
}

final case class Log(
  verbosity: String = "info"
)

object Log {
  implicit val encoder: Encoder.AsObject[Log] = deriveConfiguredEncoder
  implicit val decoder: Decoder[Log] = deriveConfiguredDecoder
}

final case class PolynoteConfig(
  listen: Listen = Listen(),
  kernel: KernelConfig = KernelConfig(),
  storage: Storage = Storage(),
  repositories: List[RepositoryConfig] = Nil,
  exclusions: List[String] = Nil,
  dependencies: Map[String, List[String]] = Map.empty,
  downloadSources: Boolean = false,
  spark: Option[SparkConfig] = None,
  behavior: Behavior = Behavior(),
  security: Security = Security(),
  ui: UI = UI(),
  credentials: Credentials = Credentials(),
  notifications: String = "",
  env: Map[String, String] = Map.empty,
  static: StaticConfig = StaticConfig(),
  log: Log = Log()
)


object PolynoteConfig {
  implicit val encoder: Encoder.AsObject[PolynoteConfig] = deriveConfiguredEncoder
  implicit val decoder: Decoder[PolynoteConfig] = deriveConfiguredDecoder[PolynoteConfig]

  private val defaultConfig = "default.yml" // we expect this to be in the directory Polynote was launched from.

  def parse(content: String): Either[Throwable, PolynoteConfig] = yaml.parser.parse(content).flatMap {
    case json if json.isBoolean => Right(Json.fromJsonObject(JsonObject.empty))
    case json if json.isObject  => Right(json)
    case json => Left(DecodingFailure(s"Invalid configuration; expected properties but found $json", Nil))
  }.flatMap(_.as[PolynoteConfig])

  private def parseFile(file: File): TaskB[Json] =
    effectBlocking(file.exists()).flatMap {
      case true => effectBlocking(new FileReader(file)).bracketAuto {
        reader => ZIO.fromEither {
          yaml.Parser.default.parse(reader).flatMap {
            case json if json.isBoolean => Right(Json.fromJsonObject(JsonObject.empty))
            case json if json.isObject  => Right(json)
            case json => Left(DecodingFailure(s"Invalid configuration; expected properties but found $json", Nil))
          }
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
      _ <- ZIO.when(parsedConfig.behavior.kernelIsolation == KernelIsolation.Never) {
        Logging.warn("Configuration value `behavior.kernel_isolation: never` is deprecated and will be removed")
      }
    } yield parsedConfig

    Logging.info(s"Loading configuration from $file") *> configIO
      .catchAll {
        case _: MatchError =>
          ZIO.succeed(PolynoteConfig()) // TODO: Handles an upstream issue with circe-yaml, on an empty config file https://github.com/circe/circe-yaml/issues/50
        case e: FileNotFoundException =>
          Logging.error(s"Configuration file $file not found; using default configuration", e).as(PolynoteConfig())
        case err: Throwable => ZIO.fail(err)
      }
  }

}
