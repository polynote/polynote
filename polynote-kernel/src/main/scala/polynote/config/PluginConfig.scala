package polynote.config

import io.circe.{Json, JsonObject}

sealed trait PluginConfig {
  import PluginConfig._
  final def asValue: Option[String] = this match {
    case Value(str) => Some(str)
    case _          => None
  }

  final def asStruct: Option[Map[String, PluginConfig]] = this match {
    case Struct(values) => Some(values)
    case _              => None
  }

  final def asArray: Option[Vector[PluginConfig]] = this match {
    case Array(values) => Some(values)
    case _             => None
  }

}

object PluginConfig {

  final case class Value(value: String) extends PluginConfig

  case object Null extends PluginConfig

  final case class Struct(values: Map[String, PluginConfig]) extends PluginConfig

  final case class Array(values: Vector[PluginConfig]) extends PluginConfig

  private def fromToString(x: Any): PluginConfig = Value(x.toString)
  private[polynote] def fromJson(json: Json): PluginConfig = json.fold(
    Null,
    fromToString,
    fromToString,
    fromToString,
    arr => Array(arr.map(fromJson)),
    obj => Struct(obj.toMap.mapValues(fromJson).toMap)
  )

  private[polynote] def fromJson(jsonObj: JsonObject): PluginConfig =
    Struct(jsonObj.toMap.mapValues(fromJson).toMap)


}
