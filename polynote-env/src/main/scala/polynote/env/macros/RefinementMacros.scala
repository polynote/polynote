package polynote.env.macros

import scala.reflect.macros.whitebox

trait RefinementMacros {
  val c: whitebox.Context

  import c.universe._

  sealed trait Failure
  case object NotRefinement extends Failure
  case object NoMember extends Failure
  case class AmbiguousMember(members: List[(Type, Symbol)]) extends Failure

  def refinementParts(typ: Type): Either[Failure, List[Type]] = typ.dealias match {
    case RefinedType(parts, _) => parts.foldLeft[Either[Failure, List[Type]]](Right(Nil)) {
      case (l@Left(_), _) => l
      case (Right(parts), part) => refinementParts(part).right.map {
        partParts => partParts ::: parts
      }
    }
    case other if other =:= typeOf[Any] => Right(List(typeOf[Any]))
    case other => Right(List(other))
  }

  def definingPart(parts: List[Type], member: Type): Either[Failure, (Type, Symbol)] = parts.flatMap {
    part =>
      part.decls.collect {
        case sym if member <:< sym.infoIn(part).resultType => part -> sym
      }
  } match {
    case one :: Nil => Right(one)
    case Nil => Left(NoMember)
    case many => Left(AmbiguousMember(many))
  }

  def intersection(parts: List[Type]): Type = parts match {
    case Nil        => weakTypeOf[Any]
    case one :: Nil => one
    case many       => c.internal.refinedType(many, NoSymbol)
  }

  def formatMembers(members: List[(Type, Symbol)]): String = members.map {
    case (typ, sym) => s"${sym.name} in ${typ.typeSymbol.name}"
  }.mkString(", ")

  def failureString(failure: Failure, in: Type, member: Type): String = failure match {
    case NotRefinement            => s"Type $in is not an intersection type"
    case NoMember                 => s"Type $in does not contain a member of type $member"
    case AmbiguousMember(members) => s"Ambiguous member; $in declares multiple members of type $member: ${formatMembers(members)}"
  }
}
