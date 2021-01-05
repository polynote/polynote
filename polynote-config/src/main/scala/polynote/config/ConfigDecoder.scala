package polynote.config

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.reflect.macros.whitebox
import scala.util.control.NonFatal

trait ConfigDecoder[T] {
  def decode(node: ConfigNode, context: DecodingContext): Either[List[DecodingError], T]
}

object ConfigDecoder extends ConfigDecoderDerivations {
  def apply[T : ConfigDecoder]: ConfigDecoder[T] = implicitly[ConfigDecoder[T]]

  def addContext(context: DecodingContext)(errs: List[String]): List[DecodingError] = errs.map {
    str => DecodingError(str, context)
  }

  def instance[T](fn: ConfigNode => Either[List[String], T]): ConfigDecoder[T] = new ConfigDecoder[T] {
    override def decode(node: ConfigNode, context: DecodingContext): Either[List[DecodingError], T] = try fn(node).left.map(addContext(context)) catch {
      case NonFatal(err) => Left(List(DecodingError(err.getMessage, context)))
    }
  }

  def instanceContext[T](fn: (ConfigNode, DecodingContext) => Either[List[DecodingError], T]): ConfigDecoder[T] = new ConfigDecoder[T] {
    override def decode(node: ConfigNode, context: DecodingContext): Either[List[DecodingError], T] = try fn(node, context) catch {
      case NonFatal(err) => Left(List(DecodingError(err.getMessage, context)))
    }
  }

  implicit val int: ConfigDecoder[Int] = instance(_.asString.right.map(_.toInt))
  implicit val string: ConfigDecoder[String] = instance(_.asString)
  implicit val bool: ConfigDecoder[Boolean] = instance {
    _.asString.right.flatMap {
      str => str.toLowerCase match {
        case "true"  => Right(true)
        case "false" => Right(false)
        case other   => Left(List(s"Invalid boolean value $other (must be true or false)"))
      }
    }
  }

  implicit def list[T : ConfigDecoder]: ConfigDecoder[List[T]] = instanceContext {
    (node, context) => node.asArray.left.map(addContext(context)).right.flatMap {
      nodes =>
        val decoded = nodes.view.zipWithIndex.map {
          case (node, index) => ConfigDecoder[T].decode(node, context.inArray(index))
        }
        val (errors, successes) = decoded.foldLeft((List.empty[DecodingError], List.empty[T])) {
          case ((errs, successes), Right(nextValue)) => (errs, nextValue :: successes)
          case ((errs, successes), Left(nextErrs))   => (errs ++ nextErrs, successes)
        }

        if (errors.nonEmpty) {
          Left(errors)
        } else {
          Right(successes.reverse)
        }
    }
  }

  implicit def strMap[T : ConfigDecoder]: ConfigDecoder[Map[String, T]] = instanceContext {
    (node, context) => node.asObject.left.map(addContext(context)).right.flatMap {
      props =>
        val decoder = ConfigDecoder[T]
        val decoded = props.map {
          case (key, node) => key -> decoder.decode(node, context.inMap(key))
        }
        val (errors, successes) = decoded.foldLeft((List.empty[DecodingError], Map.empty[String, T])) {
          case ((errs, successes), (key, Right(nextValue))) => (errs, successes + (key -> nextValue))
          case ((errs, successes), (_, Left(nextErrs)))      => (errs ++ nextErrs, successes)
        }

        if (errors.nonEmpty) {
          Left(errors)
        } else {
          Right(successes)
        }
    }
  }

  implicit val range: ConfigDecoder[Range] = instance(_.asString.right.flatMap {
    str => str.split(':') match {
      case Array(from, to) => Right(Range.inclusive(from.toInt, to.toInt))
      case _               => Left(List(s"Invalid range $str (must be e.g. 1234:4321)"))
    }
  })
}

private[config] sealed trait ConfigDecoderDerivations { this: ConfigDecoder.type =>
  implicit def derive[T <: Product]: ConfigDecoder[T] = macro ConfigDecoderDerivationMacros.derivedDecoder[T]
}

trait FieldDecoder[T] {
  def decodeField(name: String, objMap: Map[String, ConfigNode], default: Option[T], context: DecodingContext): Either[List[DecodingError], T]
}

object FieldDecoder {
  def apply[T : FieldDecoder]: FieldDecoder[T] = implicitly[FieldDecoder[T]]

  implicit def optionalField[T : ConfigDecoder]: FieldDecoder[Option[T]] = new FieldDecoder[Option[T]] {
    override def decodeField(name: String, objMap: Map[String, ConfigNode], default: Option[Option[T]], context: DecodingContext): Either[List[DecodingError], Option[T]] =
      objMap.get(name) match {
        case Some(node) => ConfigDecoder[T].decode(node, context.inObject(name)).right.map(Option.apply)
        case None => default match {
          case Some(opt) => Right(opt)
          case None      => Right(None)
        }
      }
  }

  implicit def nonOptionalField[T : ConfigDecoder]: FieldDecoder[T] = new FieldDecoder[T] {
    override def decodeField(name: String, objMap: Map[String, ConfigNode], default: Option[T], context: DecodingContext): Either[List[DecodingError], T] =
      objMap.get(name) match {
        case Some(node) => ConfigDecoder[T].decode(node, context.inObject(name))
        case None => default match {
          case Some(value) => Right(value)
          case None        => Left(List(DecodingError(s"Missing required field $name", context)))
        }
      }
  }
}

final case class DecodingError(message: String, context: DecodingContext)
final case class DecodingContext(path: List[String]) {
  def inArray(index: Int): DecodingContext = copy(path = s"[$index]" :: path)
  def inMap(key: String): DecodingContext = copy(path = "[\"" + key + "\"]" :: path)
  def inObject(field: String): DecodingContext = copy(path = s".$field" :: path)
}

object DecodingContext {
  val Empty: DecodingContext = DecodingContext(Nil)
}


private final class ConfigDecoderDerivationMacros(val c: whitebox.Context) {
  import c.universe._

  private val DecoderTC = weakTypeOf[ConfigDecoder[Any]].typeConstructor
  private def DecoderOf(t: Type): Type = appliedType(DecoderTC, t)

  private val FieldDecoderTC = weakTypeOf[FieldDecoder[Any]].typeConstructor
  private def FieldDecoderOf(t: Type): Type = appliedType(FieldDecoderTC, t)

  private val ConfigNode = weakTypeOf[ConfigNode]
  private val DecodingContext = weakTypeOf[DecodingContext]
  private val DecodingError = weakTypeOf[DecodingError]

  def derivedDecoder[T <: Product : WeakTypeTag]: Tree = {
    val T = weakTypeOf[T].dealias
    val symT = T.typeSymbol

    if (!symT.isClass) {
      c.abort(c.enclosingPosition, s"Can't derive a decoder for $T, as it is not a class")
    }

    val classT = symT.asClass

    if (!classT.isCaseClass) {
      c.abort(c.enclosingPosition, s"Can't derive a decoder for $T, as it is not a case class")
    }

    val constructorT = classT.primaryConstructor

    if (!constructorT.isConstructor) {
      c.abort(c.enclosingPosition, s"Can't derive a decoder for $T, because its primary constructor couldn't be found")
    }

    constructorT.asMethod.paramLists match {
      case Nil       => c.abort(c.enclosingPosition, s"Can't derive a decoder for $T, because its primary constructor takes no arguments")
      case args :: _ =>
        val companion = T.companion
        val fields = args.zipWithIndex.map {
          case (sym, i) =>
            val default = if (sym.asTerm.isParamWithDefault) {
              val defaultNameApply = TermName(s"apply$$default$$${i + 1}")
              val defaultNameInit = TermName(s"$$lessinit$$greater$$default$$${i + 1}")
              companion.member(defaultNameApply).orElse(companion.member(defaultNameInit)) match {
                case NoSymbol =>
                  c.warning(sym.pos, s"Field ${sym.name.decodedName} should have a default, but it didn't seem to exist")
                  None
                case sym =>
                  Some(Select(companionRef(T), sym))
              }
            } else None
            (sym.name.decodedName.toString, sym.typeSignatureIn(T).resultType, sym.pos, default)
        }

        val decoders = new mutable.HashMap[Type, Tree]()

        val errs = fields.flatMap {
          case field@(name, typ, pos, default) =>
            val fieldPos = pos match {
              case NoPosition => c.enclosingPosition
              case pos        => pos
            }

            if (!decoders.contains(typ)) {
              val decoder = c.inferImplicitValue(FieldDecoderOf(typ))
              decoders.put(typ, decoder)
              decoder match {
                case EmptyTree => List(field)
                case tree      => Nil
              }
            } else Nil
        }

        if (errs.nonEmpty) {
          errs.foreach {
            case (name, typ, pos, _) => c.error(pos, s"No decoder available for field $name: $typ (in $T)")
          }
          if (c.enclosingMacros.nonEmpty) {
            c.error(c.enclosingPosition, s"Couldn't derive a decoder for $T, because some fields couldn't be decoded.")
          }
          c.abort(c.enclosingPosition, s"Couldn't derive a decoder for $T, because some fields couldn't be decoded.")
        } else {
          val decoderName = TermName("decoder")
          val decoderDefs = decoders.toMap.map {
            case (typ, tree) =>
              val name = c.freshName(decoderName)
              typ -> (name, q"val $name: ${FieldDecoderOf(typ)} = $tree")
          }

          val decoderTrees = decoderDefs.values.toList.map(_._2)

          val (decodedNames, decodedTrees, extracts) = fields.map {
            case (name, typ, _, default) =>
              val tmpName = c.freshName(TermName(name))
              val decoderName = decoderDefs(typ)._1
              val snakeName = toSnakeCase(name)
              val defaultTree = default match {
                case Some(tree) => q"Some($tree)"
                case None       => q"None"
              }
              val decodeValue = q"$decoderName.decodeField($snakeName, objectMap, $defaultTree, context)"
              val tree = q"val $tmpName = $decodeValue"
              val extract = q"$tmpName.right.get"
              (tmpName, tree, extract)
          }.unzip3

          val errorChecks = decodedNames.map {
            tmpName => q"$tmpName.left.foreach(es => errs ++= es)"
          }

          val bufferType = weakTypeOf[ListBuffer[DecodingError]]

          val result = q"""
            new ${DecoderOf(T)} {
              def decode(node: $ConfigNode, context: $DecodingContext): Either[List[DecodingError], $T] = {
                val errs: $bufferType = new $bufferType()
                node.asObjectMap.left.map(_.map(str => new $DecodingError(str, context))).right.flatMap {
                  objectMap =>
                    ..$decoderTrees
                    ..$decodedTrees
                    ..$errorChecks
                    if (errs.nonEmpty) {
                      Left(errs.toList)
                    } else {
                      Right(new $T(..$extracts))
                    }
                }
              }
            }
          """

          c.typecheck(result)
        }
    }
  }

  private val snakeCaseRegex = raw"([a-z])([A-Z])".r
  private def toSnakeCase(str: String): String = snakeCaseRegex.replaceAllIn(str, m => s"${m.group(1)}_${m.group(2).toLowerCase}")

  private def companionRef(typ: Type): Tree = {
    val global = c.universe.asInstanceOf[scala.tools.nsc.Global]
    val pre = typ.asInstanceOf[global.Type].prefix
    val ref = global.gen.mkAttributedRef(pre, typ.typeSymbol.companion.asInstanceOf[global.Symbol])
    c.typecheck(ref.asInstanceOf[Tree])
  }
}