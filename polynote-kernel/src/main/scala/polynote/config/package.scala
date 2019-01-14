package polynote

import io.circe.{Decoder, Encoder}
import polynote.messages.{TinyList, TinyMap, TinyString}
import scodec.codecs.{Discriminated, Discriminator, byte}
import io.circe.generic.semiauto._
import scodec.Codec

package object config {
  type DependencyConfigs = TinyMap[TinyString, TinyList[TinyString]]

  sealed trait RepositoryConfig
  final case class ivy(
    base: String,
    artifactPatternOpt: Option[String] = None,
    metadataPatternOpt: Option[String] = None,
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

  object RepositoryConfig {

    implicit val discriminated: Discriminated[RepositoryConfig, Byte] = Discriminated(byte)
    implicit val encoder: Encoder[RepositoryConfig] = deriveEncoder
    implicit val decoder: Decoder[RepositoryConfig] = deriveDecoder
  }

}
