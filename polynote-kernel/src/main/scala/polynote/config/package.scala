package polynote

import io.circe.{Decoder, Json, ObjectEncoder}
import polynote.messages.{TinyList, TinyMap, TinyString}
import scodec.codecs.{Discriminated, Discriminator, byte}
import io.circe.generic.extras.{Configuration, JsonKey}
import io.circe.generic.extras.semiauto._

import cats.syntax.traverse._
import cats.syntax.either._
import cats.instances.list._
import cats.instances.either._

package object config {
  //                               lang      , deps
  type DependencyConfigs = TinyMap[TinyString, TinyList[TinyString]]

  sealed trait RepositoryConfig
  final case class ivy(
    base: String,
    @JsonKey("artifact_pattern") artifactPatternOpt: Option[String] = None,
    @JsonKey("metadata_pattern") metadataPatternOpt: Option[String] = None,
    changing: Option[Boolean] = None
  ) extends RepositoryConfig {

    def artifactPattern: String = artifactPatternOpt.getOrElse("[orgPath]/[module](_[scalaVersion])(_[sbtVersion])/[revision]/[artifact]-[revision](-[classifier]).[ext]")
    def metadataPattern: String = metadataPatternOpt.getOrElse("[orgPath]/[module](_[scalaVersion])(_[sbtVersion])/[revision]/[module](_[scalaVersion])(_[sbtVersion])-[revision]-ivy.xml")

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
    implicit val encoder: ObjectEncoder[RepositoryConfig] = deriveEncoder
    implicit val decoder: Decoder[RepositoryConfig] = deriveDecoder
  }

  implicit val circeConfig: Configuration =
    Configuration.default.withSnakeCaseConstructorNames.withSnakeCaseMemberNames.withDefaults

  implicit val mapStringStringDecoder: Decoder[Map[String, String]] = Decoder[Map[String, Json]].emap {
    jsonMap => jsonMap.toList.map {
      case (key, json) => json.fold(
        Left("No null values allowed in this map"),
        b => Right(key -> b.toString),
        n => Right(key -> n.toString),
        s => Right(key -> s),
        _ => Left("No array values allowed in this map"),
        _ => Left("No object values allowed in this map")
      )
    }.sequence.map(_.toMap)
  }

}
