package polynote.kernel.interpreter.scal

import polynote.kernel.{Completion, CompletionType, ParameterHint, ParameterHints, ScalaCompiler, Signatures}
import polynote.messages.{ShortString, TinyList, TinyString}
import cats.syntax.either._
import zio.{Fiber, Task, UIO, ZIO}
import ZIO.effect

import scala.annotation.tailrec
import scala.collection.immutable.TreeMap
import scala.reflect.internal.util.Position
import scala.util.control.NonFatal

class ScalaCompleter[Compiler <: ScalaCompiler](
  val compiler: Compiler,
  index: ClassIndexer
) {

  import compiler.global._

  def completions(cellCode: compiler.CellCode, pos: Int): UIO[List[Completion]] = {
    val position = Position.offset(cellCode.sourceFile, pos)
    def symToCompletion(sym: Symbol, inType: Type) = {
      val name = sym.name.decodedName.toString.trim // term names seem to get an extra space at the end?
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

    def completeSelect(tree: Select) = tree match {
      case Select(qual, name) if qual.tpe != null =>
        // start with completions from the qualifier's type
        val scope = qual.tpe.members.cloneScope

        // bring in completions from implicit enrichments
        val context = locateContext(tree.pos).getOrElse(NoContext)
        val ownerTpe = qual.tpe match {
          case MethodType(List(), rtpe) => rtpe
          case _ => qual.tpe
        }

        val enrichSelected = if (ownerTpe != null) {
          effect(new analyzer.ImplicitSearch(qual, definitions.functionType(List(ownerTpe), definitions.AnyTpe), isView = true, context0 = context).allImplicits).map {
            allImplicits =>
              allImplicits.foreach {
                result =>
                  val members = result.tree.tpe.finalResultType.members
                  members.foreach(scope.enterIfNew)
              }
          }
        } else ZIO.unit

        enrichSelected.map {
          _ => scopeToCompletions(scope, name, ownerTpe)
        }

      case _ => ZIO.succeed(Nil)
    }

    def completeIdent(tree: Ident) = tree match {
      case Ident(name) =>
        val nameString = name.toString
        val context = locateContext(tree.pos).getOrElse(NoContext)

        val imports = context.imports.flatMap {
          importInfo => importInfo.allImportedSymbols.filter(sym => !sym.isImplicit && sym.isPublic && sym.name.startsWith(name) && !sym.name.startsWith("deprecated"))
        }

        index.findMatches(nameString).flatMap {
          fromClasspath =>
            ZIO {
              val cellScope = context.owner.info.decls.filter(isVisibleSymbol).filter(_.name.startsWith(name)).toList.map(_.accessedOrSelf).distinct

              // somehow we're not getting the imported stuff in the context
              val prevCellScopes = if (context.owner.isClass) {
                context.owner.asClass.primaryConstructor.paramLists.headOption.toList.flatten.collect {
                  case sym if sym.name.startsWith("_input") => sym.info.nonPrivateDecls.collect {
                    case decl if (decl.isClass || decl.isMethod || decl.isType) && decl.name.startsWith(name) => decl
                  }
                }.flatten
              } else Nil

              val indexCompletions = fromClasspath.filterKeys(name => !imports.exists(_.nameString == name)).toList.flatMap {
                case (simpleName, qualifiedNames) => qualifiedNames.map {
                  case (priority, qualifiedName) => priority -> Completion(simpleName, Nil, Nil, qualifiedName, CompletionType.ClassType, Some(qualifiedName))
                }
              }.sortBy(_._1).take(10).map(_._2)
              (cellScope ++ prevCellScopes ++ imports).map(symToCompletion(_, NoType)) ++ indexCompletions
            }
        }
    }

    def completeImport(tree: Import) = tree match {
      case Import(qual, names) if qual.tpe != null =>
        val searchName = names.dropWhile(_.namePos < pos).headOption match {
          case None => TermName("")
          case Some(sel) if sel.name.decoded == "<error>" => TermName("")
          case Some(sel) => sel.name
        }

        ZIO {
          qual.tpe.members.filter(isVisibleSymbol).filter(_.isDefinedInPackage).filter(_.name.startsWith(searchName))
            .toList
            .sorted(importOrdering)
            .groupBy(_.name.decoded).values.map(_.head).toList
            .map(symToCompletion(_, NoType))
        }

      case _ => ZIO.succeed(Nil)
    }

    def completeApply(tree: Apply) = tree match {
      case Apply(fun, args) =>
        args.collectFirst {
          case arg if arg.pos != null && arg.pos.isDefined && arg.pos.includes(position) => arg
        } match {
          case Some(arg) => completeTree(arg)
          case None      => fun match {
            case Select(tree@New(_), TermName("<init>")) => completeTree(tree.tpt)
            case tree => completeTree(tree)
          }
        }
    }

    def completeTree(tree: Tree): Task[List[Completion]] = tree match {
      case tree@Select(qual, _) if qual != null       => completeSelect(tree)
      case tree@Ident(_)                              => completeIdent(tree)
      case tree@Import(qual, _) if !qual.isErrorTyped => completeImport(tree)
      case tree@Apply(_, _)                           => completeApply(tree)
      case New(tpt)                                   => completeTree(tpt)
      case tree@TypeTree() if tree.original != null   => completeTree(tree.original)
      case other =>
        val o = other
        ZIO.succeed(Nil)
    }

    effect(deepestSubtreeEndingAt(cellCode.typed, pos)).flatMap(completeTree).catchAll {
      case NonFatal(err) => ZIO.succeed(Nil)
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

  def paramHints(cellCode: compiler.CellCode, pos: Int): UIO[Option[Signatures]] = effect {
    applyTreeAt(cellCode.typed, pos).map {
      case a@Apply(fun, args) =>
        val (paramList, prevArgs, outerApply) = whichParamList(a, 0, 0)
        val whichArg = args.size

        def methodHints(method: MethodSymbol) = {
          val paramsStr = method.paramLists.map {
            pl => "(" + pl.map {
              param => s"${param.name.decodedName.toString}: ${param.typeSignatureIn(a.tpe).finalResultType.toString}"
            }.mkString(", ") + ")"
          }.mkString

          val params = method.paramLists.flatMap {
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
        }

        val hints = fun.symbol match {
          case null => Nil
          case err if err.isError =>
            fun match {
              case Select(qual, name) if !qual.isErrorTyped => qual.tpe.member(name) match {
                case sym if sym.isMethod =>
                  methodHints(sym.asMethod)
                case sym if sym.isTerm && sym.isOverloaded =>
                  sym.asTerm.alternatives.collect {
                    case sym if sym.isMethod => methodHints(sym.asMethod)
                  }.flatten
                case other =>
                  Nil
              }
              case other =>
                Nil
            }
          case method if method.isMethod => methodHints(method.asMethod)
          case _ => Nil
        }
        Signatures(hints, 0, (prevArgs + whichArg).toByte)
    }
  }.option.map(_.flatten)

  @tailrec
  private def whichParamList(tree: Apply, n: Int, nArgs: Int): (Int, Int, Apply) = tree.fun match {
    case a@Apply(_, args) => whichParamList(a, n + 1, nArgs + args.size)
    case _ => (n, nArgs, tree)
  }

  private def isVisibleSymbol(sym: Symbol) =
    sym.isPublic && !sym.isSynthetic && !sym.isConstructor && !sym.isOmittablePrefix && !sym.name.decodedName.containsChar('$')

  private def deepestSubtreeEndingAt(topTree: Tree, offset: Int): Tree = {
    var deepest: Tree = topTree
    val traverser: Traverser = new Traverser {
      private var depth = 0
      private var deepestDepth = -1
      override def traverse(tree: compiler.global.Tree): Unit = {
        if (tree.pos != null && tree.pos.isDefined && !tree.pos.isTransparent && (tree.pos.source eq topTree.pos.source) && tree.pos.end == offset && depth >= deepestDepth) {
          deepest = tree
          deepestDepth = depth
        }
        depth += 1
        super.traverse(tree)
        depth -= 1
      }
    }
    traverser.traverse(topTree)
    deepest match {
      case ValDef(_, _, _, rhs) => rhs
      case tree => tree
    }
  }

  private def applyTreeAt(tree: Tree, offset: Int): Option[Apply] = tree.collect {
    case a: Apply if a.pos != null && a.pos.isOpaqueRange && a.pos.start <= offset && a.pos.end >= offset =>
      a
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
  def apply(compiler: ScalaCompiler, indexer: ClassIndexer): ScalaCompleter[compiler.type] = new ScalaCompleter(compiler, indexer)
}