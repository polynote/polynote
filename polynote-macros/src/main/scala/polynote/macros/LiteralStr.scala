package polynote.macros


import scala.language.dynamics
import scala.language.experimental.macros
import scala.reflect.macros.whitebox

/**
  * Allows creating literal string types, which are used to create specific Python ReprsOf instances
  */
trait LiteralStr {
  type T <: String
}

object LiteralStr extends Dynamic {
  type Aux[T0 <: String] = LiteralStr { type T = T0 }

  // Provides syntax for creating a literal string type
  // (credit to shapeless's Witness for this trick)
  def selectDynamic(str: String): Any = macro StrTypeMacros.mkStrType
}

class StrTypeMacros(val c: whitebox.Context) {
  import c.universe._
  import c.internal.decorators._
  def mkStrType(str: Tree): Tree = {
    val literal = str match {
      case Literal(Constant(str: String)) => str
      case other => c.abort(str.pos, s"Expected a literal string; found $other")
    }
    val literalType = c.internal.constantType(Constant(literal))
    val carrierType = c.typecheck(tq"{ type T = $literalType }", c.TYPEmode).tpe
    Literal(Constant(())).setType(carrierType)
  }
}
