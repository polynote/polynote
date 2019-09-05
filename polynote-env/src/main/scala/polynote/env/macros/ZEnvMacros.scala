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
        case sym if sym.isMethod && sym.asMethod.isAccessor && sym.isAbstract && !B.members.exists(_.name.toString == sym.name.toString) => delegate(sym, A, q"$a.$sym")
        case sym if sym.isMethod && sym.asMethod.paramLists == List(Nil) && sym.isAbstract && !B.members.exists(_.name.toString == sym.name.toString) => delegate(sym, A, q"$a.$sym()")
      }.toList

      val bDecls = B.members.collect {
        case sym if sym.isMethod && sym.asMethod.isAccessor && sym.isAbstract => delegate(sym, B, q"$b.$sym")
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

  def createDelegateFn[T : WeakTypeTag, R : WeakTypeTag, Remainder : WeakTypeTag](provided: Tree): Tree = {
    val T = weakTypeOf[T].dealias
    val R = weakTypeOf[R].dealias
    val Remainder = weakTypeOf[Remainder].dealias

    for {
      parts          <- refinementParts(R).right
      remainderParts <- refinementParts(Remainder).right
      definer        <- definingPart(parts, T).right
    } yield {
      val (definingType, definingSymbol) = definer
      //val remainder = if (remainderParts.isEmpty) weakTypeOf[Any] else c.internal.intersectionType(remainderParts)
      val remainderDecls = remainderParts.flatMap {
        part => part.decls.collect {
          case sym if sym.isMethod && sym.asMethod.isAccessor => delegate(sym, Remainder, q"remainder.${sym.name.toTermName}")
        }
      }

      val clsName = TypeName(c.freshName("Env"))
      val classDef = ClassDef(Modifiers(), clsName, Nil, Template(
        TypeTree(weakTypeOf[AnyRef]) :: parts.map(TypeTree(_)),
        noSelfType,
        DefDef(Modifiers(), termNames.CONSTRUCTOR, Nil, Nil :: Nil, TypeTree(), Block(List(Apply(Select(Super(This(typeNames.EMPTY), typeNames.EMPTY), termNames.CONSTRUCTOR), Nil)), Literal(Constant(())))) ::
          delegate(definingSymbol, definingType, provided) :: remainderDecls
      ))

      val remainderParam = TermName("remainder")
      Function(List(ValDef(Modifiers(), remainderParam, TypeTree(Remainder), EmptyTree)), Block(
        List(classDef),
        q"new $clsName: $R"
      ))
    }

  }.fold(fail[R, T], identity)

  def provideOneTo[T : WeakTypeTag, R : WeakTypeTag, E : WeakTypeTag, A : WeakTypeTag, Remainder : WeakTypeTag](task: Tree, provided: Tree): Tree = {
    val T = weakTypeOf[T].dealias
    val R = weakTypeOf[R].dealias
    val E = weakTypeOf[E].dealias
    val A = weakTypeOf[A].dealias
    val Remainder = weakTypeOf[Remainder].dealias
    val fn = createDelegateFn[T, R, Remainder](provided)

      val result = q"""
          $task.provideSome[$Remainder]($fn): ${zio(Remainder, E, A)}
      """
      c.typecheck(result)
  }

  def provideOne[T : WeakTypeTag, R : WeakTypeTag, E : WeakTypeTag, A : WeakTypeTag, Remainder: WeakTypeTag](value: Tree)(ev: Tree): Tree = provideOneTo[T, R, E, A, Remainder](
    q"${c.prefix}.self", value
  )

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
    println(str)
    c.abort(c.enclosingPosition, str)
  }
}