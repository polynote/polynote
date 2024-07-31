package polynote

import java.io.File
import java.net.URI
import java.nio.file.{Path, Paths}
import java.util.regex.Pattern
import cats.data.{NonEmptyList, Validated, ValidatedNel}
import io.circe.{CursorOp, Decoder, DecodingFailure, Encoder, HCursor, Json}
import polynote.messages.{ShortList, ShortMap, TinyString}
import scodec.codecs.{Discriminated, Discriminator, byte}
import io.circe.generic.extras.{Configuration, JsonKey}
import io.circe.generic.extras.semiauto.{deriveConfiguredDecoder, deriveConfiguredEncoder}
import cats.syntax.traverse._
import cats.syntax.either._
import cats.instances.list._
import cats.instances.either._
import io.circe.Decoder.Result
import io.circe.generic.extras.decoding.{ConfiguredDecoder, ReprDecoder}
import io.circe.generic.extras.util.RecordToMap
import shapeless.labelled.FieldType
import shapeless.ops.hlist.{Mapped, Mapper, RightFolder, ToTraversable, Zip, ZipWithKeys}
import shapeless.ops.record.{Keys, Values}
import shapeless.{::, Annotations, Default, HList, HNil, LabelledGeneric, Lazy, Poly1, Poly2, Witness}

package object config {
  //                               lang      , deps
  type DependencyConfigs = ShortMap[TinyString, ShortList[TinyString]]

  sealed trait RepositoryConfig
  final case class ivy(
    base: String,
    @JsonKey("artifact_pattern") artifactPatternOpt: Option[String] = None,
    @JsonKey("metadata_pattern") metadataPatternOpt: Option[String] = None,
    changing: Option[Boolean] = None
  ) extends RepositoryConfig {

    def artifactPattern: String = artifactPatternOpt.filter(s => s.nonEmpty).getOrElse("[orgPath]/[module](_[scalaVersion])(_[sbtVersion])/[revision]/[artifact]-[revision](-[classifier]).[ext]")
    def metadataPattern: String = metadataPatternOpt.filter(s => s.nonEmpty).getOrElse("[orgPath]/[module](_[scalaVersion])(_[sbtVersion])/[revision]/[module](_[scalaVersion])(_[sbtVersion])-[revision]-ivy.xml")

  }

  object ivy {
    implicit val discriminator: Discriminator[RepositoryConfig, ivy, Byte] = Discriminator(0)
  }

  final case class maven(
    base: String,
    changing: Option[Boolean] = None
  ) extends RepositoryConfig

  object maven {
    implicit val discriminator: Discriminator[RepositoryConfig, maven, Byte] = Discriminator(1)
  }

  final case class pip(
    url: String
  ) extends RepositoryConfig

  object pip {
    implicit val discriminator: Discriminator[RepositoryConfig, pip, Byte] = Discriminator(2)
  }

  object RepositoryConfig {

    implicit val discriminated: Discriminated[RepositoryConfig, Byte] = Discriminated(byte)
    implicit val encoder: Encoder.AsObject[RepositoryConfig] = deriveConfiguredEncoder
    implicit val decoder: Decoder[RepositoryConfig] = deriveConfiguredDecoder
  }

  implicit val circeConfig: Configuration =
    Configuration.default.withSnakeCaseConstructorNames.withSnakeCaseMemberNames.withDefaults


  implicit val mapStringStringDecoder: Decoder[Map[String, String]] = Decoder[Map[String, Json]].emap {
    jsonMap => jsonMap.toList.map {
      case (key, json) => json.fold(
        Left("No null values allowed in this map"),
        b => Right(key -> b.toString),
        n =>
          n.toLong
            .map(_.toString).orElse(n.toBigDecimal.map(_.toString()))
            .fold(Left("No invalid numeric values allowed in this map"): Either[String, (String, String)])(v => Right(key -> v)),
        s => Right(key -> s),
        _ => Left("No array values allowed in this map"),
        _ => Left("No object values allowed in this map")
      )
    }.sequence.map(_.toMap)
  }

  implicit val patternDecoder: Decoder[Pattern] = Decoder.decodeString.emap {
    regex => Either.catchNonFatal(Pattern.compile(regex)).leftMap(err => s"Invalid regular expression: ${err.getMessage}")
  }

  implicit val patternEncoder: Encoder[Pattern] = Encoder.encodeString.contramap(_.pattern())

  implicit val pathEncoder: Encoder[Path] = Encoder.encodeString.contramap(_.toString())
  implicit val pathDecoder: Decoder[Path] = Decoder.decodeString.emap {
    pathStr => Either.catchNonFatal(new File(pathStr).toPath).leftMap(err => s"Malformed path: ${err.getMessage}")
  }

  implicit val uriEncoder: Encoder[URI] = Encoder.encodeString.contramap(_.toString())
  implicit val uriDecoder: Decoder[URI] = Decoder.decodeString.emap {
    uriStr => Either.catchNonFatal(new URI(uriStr)).leftMap(err => s"Malformed URI: ${err.getMessage}")
  }
}
