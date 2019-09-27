package polynote.env.macros

import polynote.env.ops.Location

import scala.annotation.tailrec
import scala.reflect.macros.whitebox

class LocationMacros(val c: whitebox.Context) {
  import c.universe._

  def materialize: Expr[Location] = {
    val enclosingPosition = c.enclosingPosition
    val owner = c.internal.enclosingOwner
    val name = expr(enclosingPosition.source.file.name)
    val line = expr(enclosingPosition.line)
    val method = expr(enclosingMethod(owner).map(_.name.decodedName.toString).getOrElse("<unknown>"))
    val cls = expr(enclosingClass(owner).map(_.fullName).getOrElse("<unknown>"))

    reify {
      Location(name.splice, line.splice, method.splice, cls.splice)
    }
  }

  private def expr[T](const: T): Expr[T] = c.Expr[T](Literal(Constant(const)))

  @tailrec private def enclosingMethod(sym: Symbol): Option[MethodSymbol] = sym match {
    case null | NoSymbol => None
    case m if m.isMethod => Some(m.asMethod)
    case m => enclosingMethod(sym.owner)
  }

  @tailrec private def enclosingClass(sym: Symbol): Option[ClassSymbol] = sym match {
    case null | NoSymbol => None
    case m if m.isClass => Some(m.asClass)
    case m => enclosingClass(sym.owner)
  }

}
