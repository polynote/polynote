package polynote.config

/**
  * A small AST that captures the structure of the config file representation. We've defined our own, rather than
  * reusing one from a library, to avoid coupling our interfaces to a library's JSON or YAML AST.
  *
  * This AST is really simple. It only deals with strings, arrays, and key/value objects. Any marshalling of leaf values
  * is external to the AST.
  */
sealed trait ConfigNode {
  import ConfigNode._
  final def fold[T](ifString: String => T, ifArray: Seq[ConfigNode] => T, ifObject: Seq[(String, ConfigNode)] => T): T = this match {
    case StringNode(value) => ifString(value)
    case ArrayNode(values) => ifArray(values)
    case ObjectNode(items) => ifObject(items)
  }

  final def asString: Either[List[String], String] = this match {
    case StringNode(value) => Right(value)
    case ArrayNode(_)      => Left(List("Expected a single value, but found an array"))
    case ObjectNode(_)     => Left(List("Expected a single value, but found an object"))
  }

  final def asArray: Either[List[String], Seq[ConfigNode]] = this match {
    case ArrayNode(values) => Right(values)
    case StringNode(_)     => Left(List("Expected an array, but found a single value"))
    case ObjectNode(_)     => Left(List("Expected an array, but found an object"))
  }

  final def toArray: Seq[ConfigNode] = this match {
    case ArrayNode(values) => values
    case other             => List(other)
  }

  final def asObjectNode: Either[List[String], ObjectNode] = this match {
    case obj@ObjectNode(_) => Right(obj)
    case StringNode(_)     => Left(List("Expected an object, but found a single value"))
    case ArrayNode(_)      => Left(List("Expected an object, but found an array"))
  }

  final def asObject: Either[List[String], Seq[(String, ConfigNode)]] = asObjectNode.right.map(_.values)
  final def asObjectMap: Either[List[String], Map[String, ConfigNode]] = asObjectNode.right.map(_.valuesMap)

  final def as[T : ConfigDecoder]: Either[List[DecodingError], T] = ConfigDecoder[T].decode(this, DecodingContext.Empty)
}

object ConfigNode {

  final case class StringNode(value: String) extends ConfigNode

  final case class ArrayNode(values: Seq[ConfigNode]) extends ConfigNode

  final case class ObjectNode(values: Seq[(String, ConfigNode)]) extends ConfigNode {
    lazy val valuesMap: Map[String, ConfigNode] = values.toMap
  }


}
