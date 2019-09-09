package polynote.kernel.interpreter.scal

import polynote.kernel.{Completion, CompletionType, ParameterHint, ParameterHints, ScalaCompiler, Signatures}
import polynote.messages.{ShortString, TinyList, TinyString}

import cats.syntax.either._

import scala.annotation.tailrec
import scala.reflect.internal.util.Position
import scala.util.control.NonFatal

class ScalaCompleter[Compiler <: ScalaCompiler](val compiler: Compiler) {

  import compiler.global._

  def completions(cellCode: compiler.CellCode, pos: Int): List[Completion] = {

    def symToCompletion(sym: Symbol, inType: Type) = {
      val name = sym.name.decodedName.toString
      val typ  = sym.typeSignatureIn(inType)
      val tParams = sym.typeParams.map(_.name.decodedName.toString)
      val vParams = TinyList(sym.paramss.map(_.map{p => (p.name.decodedName.toString: TinyString, compiler.unsafeFormatType(p.infoIn(typ)): ShortString)}: TinyList[(TinyString, ShortString)]))
      Completion(name, tParams, vParams, compiler.unsafeFormatType(typ), completionType(sym))
    }

    def scopeToCompletions(scope: Scope, name: Name, inType: Type): List[Completion] = {
      val isError = name.decoded == "<error>"
      val matchingSymbols = scope.filter(isVisibleSymbol).collect {
        case sym if isError || name.isEmpty || sym.name.startsWith(name) => sym.accessedOrSelf
      }.toList

      matchingSymbols.sortBy(_.name).map(symToCompletion(_, inType))
    }

    try {
      deepestSubtreeEndingAt(cellCode.typed, pos) match {
        case tree@Select(qual, name) if qual != null =>

          // start with completions from the qualifier's type
          val scope = qual.tpe.members.cloneScope

          // bring in completions from implicit enrichments
          val context = locateContext(tree.pos).getOrElse(NoContext)
          val ownerTpe = qual.tpe match {
            case MethodType(List(), rtpe) => rtpe
            case _ => qual.tpe
          }

          if (ownerTpe != null) {
            val allImplicits = new analyzer.ImplicitSearch(qual, definitions.functionType(List(ownerTpe), definitions.AnyTpe), isView = true, context0 = context).allImplicits

            allImplicits.foreach {
              result =>
                val members = result.tree.tpe.finalResultType.members
                members.foreach(scope.enterIfNew)
            }
          }
          scopeToCompletions(scope, name, qual.tpe)


        case tree@Ident(name: Name) =>
          val context = locateContext(tree.pos).getOrElse(NoContext)

          val imports = context.imports.flatMap {
            importInfo => importInfo.allImportedSymbols.filter(sym => !sym.isImplicit && sym.isPublic && sym.name.startsWith(name) && !sym.name.startsWith("deprecated"))
          }

          val cellScope = context.owner.info.decls.filter(isVisibleSymbol).toList.map(_.accessedOrSelf).distinct

          (cellScope ++ imports).map(symToCompletion(_, NoType))
//
//          NoType ->
//            (context.scope.filter(_.name.startsWith(name)).toList
//              ++ results._2.filter(_.symbol != NoSymbol).map(_.symbol)
//              ++ fromScopes
//              ++ imports)

        // this works pretty well. Really helps with imports. But is there a way we can index classes & auto-import them like IntelliJ does?
        case Import(qual: Tree, names) if !qual.isErrorTyped =>
          val searchName = names.dropWhile(_.namePos < pos).headOption match {
            case None => TermName("")
            case Some(sel) if sel.name.decoded == "<error>" => TermName("")
            case Some(sel) => sel.name
          }

          val result = qual.tpe.members.filter(isVisibleSymbol).filter(_.isDefinedInPackage)
            //.sorted(importOrdering) // TODO: monaco seems to re-sort them anyway.
            .groupBy(_.name.decoded).values.map(_.head).toList
            .map(symToCompletion(_, NoType))

          result
//          val syms = qual.tpe.members  // for imports, provide only the visible symbols, and only distinct names
//            .filter(isVisibleSymbol)        // (since imports import all overloads of a name)
//            .filter(_.isDefinedInPackage)   // and sort them so that packages come first
            //.groupBy(_.name.toString).map(_._2.toSeq.minBy(s => !s.isTerm))
            //.sortBy(_.isPackageClass)(Ordering.Boolean.reverse)

        case other =>
          val o = other
          Nil

      }
    } catch {
      case NonFatal(err) => Nil
    }
  }

  private val importOrdering: Ordering[Symbol] = new Ordering[Symbol] {
    def compare(x: Symbol, y: Symbol): Int = Ordering.Boolean.compare(y.hasPackageFlag, x.hasPackageFlag) match {
      case 0 => Ordering.Boolean.compare(x.name.isOperatorName, y.name.isOperatorName) match {
        case 0 => Ordering.String.compare(x.name.decoded, y.name.decoded)
        case s => s
      }
      case s => s
    }
  }

  def paramHints(cellCode: compiler.CellCode, pos: Int): Option[Signatures] = applyTreeAt(cellCode.typed, pos).map {
    case a@Apply(fun, args) =>
      val (paramList, prevArgs, outerApply) = whichParamList(a, 0, 0)
      val whichArg = args.size

      val hints = fun.symbol match {
        // TODO: overloads
        case method if method != null && method.isMethod =>
          val paramsStr = method.asMethod.paramLists.map {
            pl => "(" + pl.map {
              param => s"${param.name.decodedName.toString}: ${param.typeSignatureIn(a.tpe).finalResultType.toString}"
            }.mkString(", ") + ")"
          }.mkString

          val params = method.asMethod.paramLists.flatMap {
            pl => pl.map {
              param => ParameterHint(
                TinyString(param.name.decodedName.toString),
                TinyString(param.typeSignatureIn(a.tpe).finalResultType.toString),
                None  // TODO
              )
            }
          }

          List(ParameterHints(
            method.name.decodedName.toString + paramsStr,
            None,
            params
          ))

        case _ => Nil
      }
      Signatures(hints, 0, (prevArgs + whichArg).toByte)
  }

  @tailrec
  private def whichParamList(tree: Apply, n: Int, nArgs: Int): (Int, Int, Apply) = tree.fun match {
    case a@Apply(_, args) => whichParamList(a, n + 1, nArgs + args.size)
    case _ => (n, nArgs, tree)
  }

  private def isVisibleSymbol(sym: Symbol) =
    sym.isPublic && !sym.isSynthetic && !sym.isConstructor && !sym.isOmittablePrefix && !sym.name.decodedName.containsChar('$')

  private def deepestSubtreeEndingAt(tree: Tree, offset: Int): Tree = {
    var deepest: Tree = tree
    val traverser: Traverser = new Traverser {
      private var depth = 0
      private var deepestDepth = -1
      override def traverse(tree: compiler.global.Tree): Unit = {
        if (tree.pos != null && tree.pos.isOpaqueRange && tree.pos.end == offset && depth >= deepestDepth) {
          deepest = tree
          deepestDepth = depth
        }
        depth += 1
        super.traverse(tree)
        depth -= 1
      }
    }
    traverser.traverse(tree)
    deepest
  }

  private def applyTreeAt(tree: Tree, offset: Int): Option[Apply] = tree.collect {
    case a: Apply if a.pos != null && a.pos.isOpaqueRange && a.pos.start <= offset && a.pos.end >= offset => a
  }.headOption

  private def treesAt(tree: Tree, targetPos: Position): List[Tree] = if (tree.pos.properlyIncludes(targetPos)) {
    tree.children.collect {
      case t if t.pos.properlyIncludes(targetPos) => treesAt(t, targetPos)
    }.flatten match {
      case Nil => List(tree)
      case trees => trees
    }
  } else Nil

  def completionType(sym: Symbol): CompletionType =
    if (sym.isAccessor)
      CompletionType.Field
    else if (sym.isMethod)
      CompletionType.Method
    else if (sym.isPackageObjectOrClass)
      CompletionType.Package
    else if (sym.isTrait)
      CompletionType.TraitType
    else if (sym.isModule)
      CompletionType.Module
    else if (sym.isClass)
      CompletionType.ClassType
    else if (sym.isVariable)
      CompletionType.Term
    else CompletionType.Unknown

}

object ScalaCompleter {
  def apply(compiler: ScalaCompiler): ScalaCompleter[compiler.type] = new ScalaCompleter(compiler)
}