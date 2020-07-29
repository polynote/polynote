package polynote

import java.io.File
import java.net.URI
import java.nio.file.{Path, Paths}
import java.util.regex.Pattern

import cats.data.{NonEmptyList, Validated, ValidatedNel}
import io.circe.{CursorOp, Decoder, DecodingFailure, Encoder, HCursor, Json, ObjectEncoder}
import polynote.messages.{TinyList, TinyMap, TinyString}
import scodec.codecs.{Discriminated, Discriminator, byte}
import io.circe.generic.extras.{Configuration, JsonKey}
import io.circe.generic.extras.semiauto._
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
  type DependencyConfigs = TinyMap[TinyString, TinyList[TinyString]]

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
    implicit val encoder: ObjectEncoder[RepositoryConfig] = deriveEncoder
    implicit val decoder: Decoder[RepositoryConfig] = deriveDecoder
  }

  implicit val circeConfig: Configuration =
    Configuration.default.withSnakeCaseConstructorNames.withSnakeCaseMemberNames.withDefaults

  abstract class ValidatedConfigDecoder[A] extends ConfiguredDecoder[A](circeConfig.copy(useDefaults = false))

  object ValidatedConfigDecoder {
    object optionalOrDefault extends Poly2 {
      implicit def someDefault[K, A, OutT <: HList]: Case.Aux[(FieldType[K, Option[A]], Some[A]), ValidatedNel[DecodingFailure, OutT], ValidatedNel[DecodingFailure, A :: OutT]] =
        at[(FieldType[K, Option[A]], Some[A]), ValidatedNel[DecodingFailure, OutT]] {
          case ((in, default), accum) => accum.andThen(outT => Validated.validNel(in.getOrElse(default.get) :: outT))
        }

      implicit def noDefault[K <: Symbol, A, OutT <: HList](implicit name: Witness.Aux[K]): Case.Aux[(FieldType[K, Option[A]], None.type), ValidatedNel[DecodingFailure, OutT], ValidatedNel[DecodingFailure, A :: OutT]] =
        at[(FieldType[K, Option[A]], None.type), ValidatedNel[DecodingFailure, OutT]] {
          (in, accum) => in._1.asInstanceOf[Option[A]] match {
            case Some(a) => accum.andThen(outT => Validated.Valid(a :: outT))
            case None =>
              val failure = DecodingFailure(s"Missing non-optional field ${name.value.name}", List(CursorOp.Field(name.value.name)))
              Validated.Invalid(accum.swap.map(failure :: _).getOrElse(NonEmptyList(failure, Nil)))
          }
        }
    }

    /**
      * This complex machine is for allowing us to keep our default values for the config ADT, but still validate the
      * configs properly during decoding. Circe (at least the version available to us given Scala 2.11 constraint) silently
      * drops decoding errors when there's a default value, and just uses the default instead. This derivation does
      * something different:
      * - Derive the shapeless record type `OR` that corresponds to the case class `A`, but with all values wrapped in `Option`.
      * - Derive the circe decoder for `OR`, which allows values to be unspecified but does not drop validation errors.
      * - Put the optionalized record together with the defaults from the case class constructor, and do another round of
      *   validation where a `None` is replaced by the default (if it exists) or a validation error (if there is no default).
      * - Within the validated structure, go back to the original record type `R` that corresponds to the case class and
      *   use the shapeless LabelledGeneric to translate it to a validated case class instance.
      */
    implicit def deriveConfigDecoder[A, R <: HList, F <: HList, V <: HList, OV <: HList, OR <: HList, D <: HList, ZD <: HList, K <: HList](
      implicit
      gen: LabelledGeneric.Aux[A, R],
      fields: Keys.Aux[R, F],
      values: Values.Aux[R, V],
      optionized: Mapped.Aux[V, Option, OV],
      optionizedRecord: ZipWithKeys.Aux[F, OV, OR],
      decodeR: Lazy[ReprDecoder[OR]],
      defaults: Default.Aux[A, D],
      fieldsToList: ToTraversable.Aux[F, List, Symbol],
      keys: Annotations.Aux[JsonKey, A, K],
      keysToList: ToTraversable.Aux[K, List, Option[JsonKey]],
      zipWithDefaults: Zip.Aux[OR :: D :: HNil, ZD],
      mapper: RightFolder.Aux[ZD, ValidatedNel[DecodingFailure, HNil], optionalOrDefault.type, ValidatedNel[DecodingFailure, V]],
      relabel: ZipWithKeys.Aux[F, V, R]
    ): ValidatedConfigDecoder[A] = new ValidatedConfigDecoder[A] {
      override def apply(c: HCursor): Result[A] = decodeR.value.configuredDecodeAccumulating(c)(circeConfig.transformMemberNames, circeConfig.transformConstructorNames, Map.empty, circeConfig.discriminator).andThen {
        OR => mapper(zipWithDefaults(OR :: defaults() :: HNil), Validated.valid(HNil))
      }.map {
        V => gen.from(relabel(V))
      }.toEither.leftMap {
        errs =>
          val errsList = errs.toList
          val combinedErrs = ("" :: errsList.map(_.message)).mkString("\n- ")
          DecodingFailure(s"Configuration is invalid:$combinedErrs", errsList.flatMap(_.history))
      }
    }
  }

  def deriveConfigDecoder[A](implicit decoder: Lazy[ValidatedConfigDecoder[A]]): Decoder[A] = decoder.value

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
