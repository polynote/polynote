package polynote.kernel.interpreter.scal

import polynote.kernel.{Completion, CompletionType, ParameterHint, ParameterHints, ScalaCompiler, Signatures}
import polynote.messages.{ShortString, TinyList, TinyString}

import scala.annotation.tailrec
import scala.reflect.internal.util.Position
import scala.util.control.NonFatal

class ScalaCompleter[Compiler <: ScalaCompiler](val compiler: Compiler) {

  import compiler.global._

  def completions(cellCode: compiler.CellCode, pos: Int): List[Completion] = try {
    deepestSubtreeEndingAt(cellCode.typed, pos) match {
      case tree@Select(qual, name) if qual != null =>
        val fromContext = locateContext(tree.pos).map(_.scope).getOrElse(newScope)
        val fromType = qual.tpe.members

        val matchingSymbols = (fromContext ++ fromType).collect {
          case sym if sym.isPublic && !sym.isSynthetic && (name.isEmpty || sym.name.startsWith(name)) => sym.accessedOrSelf
        }.toList

        matchingSymbols.sortBy(_.name).map {
          sym =>
            val name = sym.name.decodedName.toString
            val typ  = sym.typeSignatureIn(qual.tpe)
            val tParams = sym.typeParams.map(_.name.decodedName.toString)
            val vParams = TinyList(sym.paramss.map(_.map{p => (p.name.decodedName.toString: TinyString, compiler.unsafeFormatType(p.infoIn(typ)): ShortString)}: TinyList[(TinyString, ShortString)]))
            Completion(name, tParams, vParams, compiler.unsafeFormatType(typ), completionType(sym))
        }
    }
  } catch {
    case NonFatal(_) => Nil
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
        if (tree.pos != null && tree.pos.end == offset && depth >= deepestDepth) {
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
    case a: Apply if a.pos != null && a.pos.start <= offset && a.pos.end >= offset => a
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