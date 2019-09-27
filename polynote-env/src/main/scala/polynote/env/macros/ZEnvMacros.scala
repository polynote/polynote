package polynote.env.macros

import zio.ZIO
import polynote.env.ops.Enrich

import scala.reflect.macros.whitebox

class ZEnvMacros(val c: whitebox.Context) extends RefinementMacros {
  import c.universe._
  private def zio(R: Type, E: Type, A: Type): Type =
    appliedType(
      weakTypeOf[ZIO[_, _, _]].typeConstructor, R, E, A)

  private def delegate(sym: Symbol, in: Type, to: Tree) =
    q"final val ${sym.name.toTermName}: ${sym.infoIn(in).resultType} = $to"

  def createDelegate[A : WeakTypeTag, B : WeakTypeTag](a: Tree, b: Tree): Tree = {
    val A = weakTypeOf[A].dealias
    val B = weakTypeOf[B].dealias
    val Result = weakTypeOf[A with B]
    for {
      aParts <- refinementParts(A).right
      bParts <- refinementParts(B).right
    } yield {
      val clsName = TypeName(c.freshName("Env"))
      val aDecls = A.members.collect {
        case sym if sym.isMethod && sym.asMethod.paramLists == Nil && sym.isAbstract && !B.members.exists(_.name.toString == sym.name.toString) => delegate(sym, A, q"$a.$sym")
        case sym if sym.isMethod && sym.asMethod.paramLists == List(Nil) && sym.isAbstract && !B.members.exists(_.name.toString == sym.name.toString) => delegate(sym, A, q"$a.$sym()")
      }.toList

      val bDecls = B.members.collect {
        case sym if sym.isMethod && sym.asMethod.paramLists == Nil && sym.isAbstract => delegate(sym, B, q"$b.$sym")
        case sym if sym.isMethod && sym.asMethod.paramLists == List(Nil) && sym.isAbstract => delegate(sym, B, q"$b.$sym()")
      }.toList

      val classDef = ClassDef(Modifiers(), clsName, Nil, Template(
        TypeTree(weakTypeOf[AnyRef]) :: ((aParts ::: bParts).distinct).map(TypeTree(_)),
        noSelfType,
        DefDef(Modifiers(), termNames.CONSTRUCTOR, Nil, Nil :: Nil, TypeTree(), Block(List(Apply(Select(Super(This(typeNames.EMPTY), typeNames.EMPTY), termNames.CONSTRUCTOR), Nil)), Literal(Constant(())))) ::
          aDecls ::: bDecls
      ))


      Block(List(classDef), q"new $clsName: $Result")
    }
  }.fold(fail[A, B], identity)

  def mkEnrich[A : WeakTypeTag, B : WeakTypeTag]: Tree = {
    val A = weakTypeOf[A].dealias
    val B = weakTypeOf[B].dealias
    val ResultType = weakTypeOf[Enrich[A, B]]
    val result = q"""
       new $ResultType {
         def apply(a: $A, b: $B): $A with $B = ${createDelegate[A, B](q"a", q"b")}
       }
     """

    try {
      c.typecheck(result)
    } catch {
      case err: Throwable =>
        val e = err
        throw(e)
    }
  }

  def fail[A : WeakTypeTag, B : WeakTypeTag](err: Failure): Nothing = {
    val str = failureString(err, weakTypeOf[A], weakTypeOf[B])
    c.abort(c.enclosingPosition, str)
  }
}