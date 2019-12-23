package polynote.kernel.interpreter.scal

import polynote.kernel.{Completion, CompletionType, ParameterHint, ParameterHints, ScalaCompiler, Signatures}
import polynote.messages.{ShortString, TinyList, TinyString}
import cats.syntax.either._
import zio.{Fiber, RIO, Task, UIO, URIO, ZIO}
import ZIO.{effect, effectTotal}
import polynote.kernel.ScalaCompiler.OriginalPos
import zio.blocking.{Blocking, effectBlocking}

import scala.annotation.tailrec
import scala.collection.immutable.TreeMap
import scala.collection.mutable
import scala.reflect.internal.util.Position
import scala.util.control.NonFatal

class ScalaCompleter[Compiler <: ScalaCompiler](
  val compiler: Compiler,
  index: ClassIndexer
) {

  import compiler.global._

  def completions(cellCode: compiler.CellCode, pos: Int): URIO[Blocking, List[Completion]] = {
    val position = Position.offset(cellCode.sourceFile, pos)
    //lazy val importInfos = (cellCode.wrappedImports ++ cellCode.compiledImports).map(new analyzer.ImportInfo(_, 1))
    def symToCompletion(sym: Symbol, inType: Type) = {
      val name = sym.name.decodedName.toString.trim // term names seem to get an extra space at the end?
      val typ  = sym.typeSignatureIn(inType).resultType
      val tParams = sym.typeParams.map(_.name.decodedName.toString)
      val vParams = TinyList(sym.paramss.map(_.map{p => (p.name.decodedName.toString: TinyString, compiler.unsafeFormatType(p.infoIn(typ)): ShortString)}: TinyList[(TinyString, ShortString)]))
      Completion(name, tParams, vParams, compiler.unsafeFormatType(typ), completionType(sym))
    }

    def memberToCompletion(result: Member) = {
      symToCompletion(result.sym, result.tpe).copy(resultType = compiler.unsafeFormatType(result.tpe.resultType))
    }

    def completeSelect(tree: Select) = tree match {
      case sel@Select(qual, name) if qual.tpe != null =>
        val isErrorOrEmpty = name.decoded == "<error>" || name.isEmpty
        effectBlocking {

          val context = locateContext(position).getOrElse(NoContext)
          val fromCompiler = typeCompletions(context, sel)

          fromCompiler.results.filter {
            result => result.accessible && (isErrorOrEmpty || result.sym.name.startsWith(name))
          }
        }.map {
          results =>
            results.map(memberToCompletion).distinct
        }

      case _ =>
        ZIO.succeed(Nil)
    }

    def completeIdent(tree: Ident) =
      effectBlocking(compiler.global.completionsAt(position)).map {
        result =>
          result.matchingResults().map(memberToCompletion).distinct
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

    @tailrec def completeTree(tree: Tree): RIO[Blocking, List[Completion]] = tree match {
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


    (effectBlocking(cellCode.typed) *> effectBlocking(locateTree(position)).flatMap(completeTree)).catchAll {
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
                TinyString(param.typeSignatureIn(fun.tpe).finalResultType.toString),
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

  private def deepestSubtreeEndingAt(stats: List[Tree], pos: Position): Option[Tree] = {
    val candidate = stats.filterNot(tree => tree.pos == null || !tree.pos.isDefined || tree.pos.isTransparent || !tree.pos.properlyIncludes(pos))
      .headOption

    candidate.flatMap {
      baseTree => baseTree.collect {
        case tree if tree.pos != null && tree.pos.isDefined && tree.pos.end == pos.point =>
          val a = tree.attachments.get[OriginalPos]
          tree
      }.lastOption
    }
  }

  // the compiler's completionsAt method has a bug; it causes an exception if the name is empty
  // (e.g. `foo.`) which is a pretty common case. So there will be a fair amount of copypasta here,
  // as the component methods we'd need are all private. This bit is copied from interactive.Global#typeMembers
  private def typeMembers(context: Context, tree: Tree) = {

    // had to copypasta this class as well, since it's private.
    class Members[M <: Member] extends mutable.LinkedHashMap[Name, Set[M]] {
      import scala.reflect.internal.Flags._
      override def default(key: Name) = Set()

      private def matching(sym: Symbol, symtpe: Type, ms: Set[M]): Option[M] = ms.find { m =>
        (m.sym.name == sym.name) && (m.sym.isType || (m.tpe matches symtpe))
      }

      private def keepSecond(m: M, sym: Symbol, implicitlyAdded: Boolean): Boolean =
        m.sym.hasFlag(ACCESSOR | PARAMACCESSOR) &&
          !sym.hasFlag(ACCESSOR | PARAMACCESSOR) &&
          (!implicitlyAdded || m.implicitlyAdded)

      def add(sym: Symbol, pre: Type, implicitlyAdded: Boolean)(toMember: (Symbol, Type) => M) {
        if ((sym.isGetter || sym.isSetter) && sym.accessed != NoSymbol) {
          add(sym.accessed, pre, implicitlyAdded)(toMember)
        } else if (!sym.name.decodedName.containsName("$") && !sym.isError && !sym.isArtifact && sym.hasRawInfo) {
          val symtpe = pre.memberType(sym) onTypeError ErrorType
          matching(sym, symtpe, this(sym.name)) match {
            case Some(m) =>
              if (keepSecond(m, sym, implicitlyAdded)) {
                //print(" -+ "+sym.name)
                this(sym.name) = this(sym.name) - m + toMember(sym, symtpe)
              }
            case None =>
              //print(" + "+sym.name)
              this(sym.name) = this(sym.name) + toMember(sym, symtpe)
          }
        }
      }

      def addNonShadowed(other: Members[M]): Unit = {
        for ((name, ms) <- other)
          if (ms.nonEmpty && this(name).isEmpty) this(name) = ms
      }

      def allMembers: List[M] = values.toList.flatten
    }

    val superAccess = tree.isInstanceOf[Super]
    val members = new Members[TypeMember]
    def addTypeMember(sym: Symbol, pre: Type, inherited: Boolean, viaView: Symbol): Unit = {
      val implicitlyAdded = viaView != NoSymbol
      members.add(sym, pre, implicitlyAdded) { (s, st) =>
        val result = new TypeMember(s, st,
          context.isAccessible(if (s.hasGetter) s.getterIn(s.owner) else s, pre, superAccess && !implicitlyAdded),
          inherited,
          viaView)
        result.prefix = pre
        result

      }
    }

    import analyzer.{SearchResult, ImplicitSearch}
    import definitions.{functionType, AnyTpe}
    /** Create a function application of a given view function to `tree` and typechecked it.
      */
    def viewApply(view: SearchResult): Tree = {
      assert(view.tree != EmptyTree)
      analyzer.newTyper(context.makeImplicit(reportAmbiguousErrors = false))
        .typed(Apply(view.tree, List(tree)) setPos tree.pos)
        .onTypeError(EmptyTree)
    }

    val pre = stabilizedType(tree)

    val ownerTpe = tree.tpe match {
      case ImportType(expr) => expr.tpe
      case null => pre
      case MethodType(List(), rtpe) => rtpe
      case _ => tree.tpe
    }

    //print("add members")
    for (sym <- ownerTpe.members)
      addTypeMember(sym, pre, sym.owner != ownerTpe.typeSymbol, NoSymbol)
    members.allMembers #:: {
      //print("\nadd enrichment")
      val applicableViews: List[SearchResult] =
        if (ownerTpe.isErroneous) List()
        else new ImplicitSearch(
          tree, functionType(List(ownerTpe), AnyTpe), isView = true,
          context0 = context.makeImplicit(reportAmbiguousErrors = false)).allImplicits
      for (view <- applicableViews) {
        val vtree = viewApply(view)
        val vpre = stabilizedType(vtree)
        for (sym <- vtree.tpe.members if sym.isTerm) {
          addTypeMember(sym, vpre, inherited = false, view.tree.symbol)
        }
      }
      //println()
      Stream(members.allMembers)
    }
  }

  // copied from inner method typeCompletions in interactive.Global#completionsAt. But without fatal bug.
  private def typeCompletions(context: Context, tree: Select) = {
    val allTypeMembers = typeMembers(context, tree.qualifier).toList.flatten
    CompletionResult.TypeMembers(0, tree.qualifier, tree, allTypeMembers, tree.name)
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
    else if (sym.isVariable || sym.isVal)
      CompletionType.Term
    else
      CompletionType.Unknown

}

object ScalaCompleter {
  def apply(compiler: ScalaCompiler, indexer: ClassIndexer): ScalaCompleter[compiler.type] = new ScalaCompleter(compiler, indexer)
}