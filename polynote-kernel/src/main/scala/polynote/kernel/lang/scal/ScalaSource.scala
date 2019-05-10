package polynote.kernel.lang.scal

import cats.data.Ior
import cats.effect.IO
import cats.syntax.either._
import polynote.kernel.EmptyCell
import polynote.kernel.lang.LanguageInterpreter
import polynote.kernel.util.{CellContext, KernelContext, KernelReporter}
import polynote.messages.CellID

import scala.annotation.tailrec
import scala.collection.immutable.{ListMap, Queue}
import scala.reflect.internal.util.{ListOfNil, Position, RangePosition, SourceFile}
import scala.tools.nsc.interactive.Global

/**
  * Captures some Scala source in the context of a notebook, and provides compilation operations.
  *
  * TODO: the refined Interpreter type is really annoying; dictated by Global's path-dependent types. Can that be
  *       unwound?
  */
class ScalaSource[G <: Global](
  val global: G,
  val cellContext: CellContext,
  previousSources: List[ScalaSource[G]],
  notebookPackage: String,
  afterParse: => Ior[Throwable, List[G#Tree]],
  prepend: Option[List[G#Tree]] = None
) {

  import global.{Tree, atPos}

  private val reporter = global.reporter.asInstanceOf[KernelReporter]

  private def withCompiler[A](fn: => A): Either[Throwable, A] = Either.catchNonFatal {
    global.ask(() => fn)
  }

  def moduleRef: global.Select = global.Select(global.Ident(global.TermName(notebookPackage)), moduleName)
  def moduleInstanceRef: global.Tree = global.Select(moduleRef, global.TermName("INSTANCE"))

  private def ensurePositions(sourceFile: SourceFile, tree: Tree, parentPos: Position): Position = {
    if (tree.pos != null && tree.pos.isDefined) {
      tree.children.foreach(ensurePositions(sourceFile, _, tree.pos))
      tree.pos
    } else if (tree.children.nonEmpty) {
      val pos = tree.children.foldLeft(new RangePosition(sourceFile, parentPos.start, parentPos.point, parentPos.end)) {
        (currentPos, child) =>
          val captured = ensurePositions(sourceFile, child, currentPos)
          val end = math.max(captured.end, currentPos.end)
          new RangePosition(sourceFile, end, end, end)
      }
      tree.setPos(pos)
      pos
    } else {
      val pos = new RangePosition(sourceFile, parentPos.start, parentPos.start, parentPos.start)
      tree.setPos(pos)
      pos
    }
  }

  private def forcePos(pos: Position, tree: Tree): Tree = {
    val transformer = new global.Transformer {
      override def transform(tree: global.Tree): global.Tree = {
        super.transform(tree).setPos(pos)
      }
    }
    transformer.transform(tree)
  }

  private def reassignThis(to: global.Tree)(tree: global.Tree): global.Tree = {
    val transformer = new global.Transformer {
      override def transform(tree: global.Tree): global.Tree = tree match {
        case global.This(sym) => to
        case _ => super.transform(tree)
      }
    }
    transformer.transform(tree)
  }

  val cellName: String = ScalaSource.nameFor(cellContext)

  // the parsed trees, but only successful if there weren't any parse errors
  lazy val parsed: Ior[Throwable, List[Tree]] = afterParse.asInstanceOf[Ior[Throwable, List[Tree]]]

  lazy val successfulParse: Either[Throwable, List[Tree]] = parsed match {
    case Ior.Both(err, _) => Left(err)
    case Ior.Right(trees) => Right(trees)
    case Ior.Left(err) => Left(err)
  }

  // the trees that came out of the parser, ignoring any parse errors
  private lazy val parsedTrees = parsed match {
    case Ior.Both(_, trees) if trees.nonEmpty => Right(trees)
    case Ior.Both(_, _)                       => Left(EmptyCell)
    case Ior.Right(trees) if trees.nonEmpty   => Right(trees)
    case Ior.Right(_)                         => Left(EmptyCell)
    case Ior.Left(err)                        => Left(err)
  }

  private lazy val executionId = global.currentRunId

  lazy val moduleName: global.TermName = global.TermName(s"Eval$$$cellName$$$executionId")

  // the code, rewritten so that the last expression, if it is a free expression, is assigned to Out
  // returns the name of the last expression (Out unless it was a ValDef, in which case it's the name from the ValDef)
  private lazy val results = parsedTrees.flatMap {
    trees => Either.catchNonFatal {
      trees.last match {
        case global.ValDef(mods, name, _, _) if !mods.isPrivate => (Some(name), trees)
        case t@global.ValDef(mods, name, tpt, _) =>
          val accessorName = global.TermName("Out")
          val pos = t.pos.withStart(t.pos.end)
          (Some(accessorName), trees.dropRight(1) :+
            global.ValDef(global.Modifiers(), accessorName, tpt, global.Ident(name).setPos(pos)).setPos(pos))
        case expr if expr.isTerm =>
          val accessorName = global.TermName("Out")
          (Some(accessorName), trees.dropRight(1) :+
            global.ValDef(global.Modifiers(), accessorName, global.TypeTree(global.NoType), expr).setPos(expr.pos))
        case _ => (None, trees)
      }
    }
  }

  // The code, decorated with extra calls to update the progress and position within the cell during execution
  private lazy val decoratedTrees = results.map {
    case (_, trees) =>
      import global.Quasiquote
      val numTrees = trees.size
      trees.zipWithIndex.flatMap {
        case (tree, index) =>
          val treeProgress = index.toDouble / numTrees
          val lineStr = s"Line ${tree.pos.line}"
          // code to notify kernel of progress in the cell
          def setProgress(detail: String) = q"""polynote.runtime.Runtime.currentRuntime.setProgress($treeProgress, $detail)"""
          def setPos(mark: Tree) =
            if(mark.pos.isRange)
              Some(q"""polynote.runtime.Runtime.currentRuntime.setExecutionStatus(${mark.pos.start}, ${mark.pos.end})""")
            else None

          def wrapWithProgress(name: String, tree: Tree): List[Tree] =
            setPos(tree).toList ++ List(setProgress(name), tree)

          tree match {
            case tree: global.ValDef => wrapWithProgress(tree.name.decodedName.toString, tree)
            case tree: global.MemberDef => List(tree)
            case tree: global.Import => List(tree)
            case tree => wrapWithProgress(lineStr, tree)
          }
      }
  }

  lazy val resultName: Option[global.TermName] = results.right.toOption.flatMap(_._1)

  lazy val wrapped: Either[Throwable, Tree] = decoratedTrees.right.flatMap {
    trees => Either.catchNonFatal {
      val source = trees.head.pos.source
      val endPos = trees.map(_.pos).collect {
        case pos if pos != null && pos.isRange => pos.end
      } match {
        case Nil => 0
        case poss => poss.max
      }

      val range = new RangePosition(source, 0, 0, endPos)
      val beginning = new RangePosition(source, 0, 0, 0)
      val end = new RangePosition(source, endPos, endPos, endPos)

      import global.Quasiquote
      import global.treeBuilder.scalaDot

      def constructor =
        global.DefDef(
          global.NoMods,
          global.nme.CONSTRUCTOR,
          Nil,
          ListOfNil,
          atPos(beginning)(global.TypeTree()),
          atPos(beginning)(global.Block(
            atPos(beginning)(global.PrimarySuperCall(ListOfNil)), atPos(beginning)(global.gen.mkSyntheticUnit()))))

      val usedIdents = trees.flatMap {
        tree => tree.collect {
          case global.Ident(name) => name.toString
        }
      }.toSet

      // import everything imported by previous cells...
      val directImports: List[global.Tree] = previousSources.flatMap(_.directImports.asInstanceOf[List[global.Tree]])

      //... and also import all public declarations from previous cells
      val impliedImports = previousSources.foldLeft(ListMap.empty[String, (global.ModuleSymbol, global.Name)]) {
        (accum, next) =>
          // grab this source's compiled module
          val moduleSym = next.compiledModule.right.get.asModule

          // grab all the non-private members declared in the module
          accum ++ next.decls.right.get.map {
            decl =>
              // Mapping with decl's name to clobber duplicates, keep track of decl Name and the Module it came from
              decl.name.toString -> (moduleSym.asInstanceOf[global.ModuleSymbol], decl.name.asInstanceOf[global.Name])
          }.toMap
        }.filter(usedIdents contains _._1).toList.groupBy(_._2._1).flatMap {
          case (module, imports) =>
            val localValName = global.freshTermName(module.name.toString + "$INSTANCE")(global.currentFreshNameCreator)
            val localVal = q"val $localValName = $module.INSTANCE"
            localVal +: imports.map {
              case (_, (_, name)) => q"import $localValName.$name"
            }
        }.toList

      // This gnarly thing is building a synthetic object {} tree around the statements.
      // Quasiquotes won't do the trick, because we have to assign positions to every tree or the compiler freaks out.
      // We just smush the positions of the wrapper code to the beginning and end.
      // We don't just make a new code string and re-parse, because we want the real positions in the cell to be
      // reported in any compile errors on the client side.
      // TODO: position validation seems to happen at parser phase – could tell Global to skip that and avoid all of this?
      val wrappedSource = atPos(range) {
        global.PackageDef(
          atPos(beginning)(global.Ident(global.TermName(notebookPackage))),
          directImports.map(forcePos(beginning, _)).map(global.resetAttrs) ::: List(
            atPos(range) {
              global.ClassDef(
                global.Modifiers(),
                moduleName.toTypeName,
                Nil,
                atPos(range) {
                  global.Template(
                    List(atPos(beginning)(scalaDot(global.typeNames.AnyRef)), atPos(beginning)(scalaDot(global.typeNames.Serializable))), // extends AnyRef with Serializable
                    atPos(beginning)(global.noSelfType.copy()),
                    atPos(beginning)(constructor) ::
                      impliedImports.map(atPos(beginning)) :::
                      prepend.toList.flatten.asInstanceOf[List[global.Tree]].map(atPos(beginning)) :::
                      trees)
                })
            },
            atPos(end) {
              global.ModuleDef(
                global.Modifiers(),
                moduleName,
                atPos(end) {
                  global.Template(
                    List(atPos(end)(scalaDot(global.typeNames.AnyRef))),
                    atPos(end)(global.noSelfType.copy()),
                    atPos(end)(constructor) :: atPos(end)(q"val INSTANCE = new ${moduleName.toTypeName}") :: Nil
                  )
                }
              )
            }
          ))
      }
      ensurePositions(
        source,
        wrappedSource,
        range
      )

      wrappedSource
    }
  }

  private lazy val compileUnit: Either[Throwable, global.CompilationUnit] = wrapped.right.map {
    tree =>
      val sourceFile = CellSourceFile(cellName)
      val unit = new global.RichCompilationUnit(sourceFile) //new global.CompilationUnit(CellSourceFile(id))
      unit.body = tree
      unit.status = global.JustParsed
      global.unitOfFile.put(sourceFile.file, unit)
      unit
  }

  // just type the tree – doesn't advance to subsequent phases (for completions etc)
  lazy val quickTyped: Either[Throwable, global.Tree] = for {
    unit <- compileUnit
    tree <- Either.catchNonFatal {
      val run = new global.Run()
      run.namerPhase.asInstanceOf[global.GlobalPhase].apply(unit)
      run.typerPhase.asInstanceOf[global.GlobalPhase].apply(unit)
      global.exitingTyper(unit.body)
    }
  } yield tree

  // The deepest enclosing tree of the given position, after typing
  // attempts to find a tree that has a type, but otherwise returns the deepest tree
  def typedTreeAt(offset: Int): Either[Throwable, Option[global.Tree]] = {
    def treesAtPos(tree: Tree, targetPos: Position): List[Tree] = if (tree.pos.properlyIncludes(targetPos)) {
      tree.children.collect {
        case t if t.pos.properlyIncludes(targetPos) => treesAtPos(t, targetPos)
      }.flatten match {
        case Nil => List(tree)
        case trees => trees
      }
    } else Nil

    for {
      unit <- compileUnit
      tree <- quickTyped
    } yield {
      val trees = treesAtPos(tree, Position.offset(unit.source, offset))
      trees.find {
        tree => tree.tpe != null && tree.tpe != global.NoType
      } orElse trees.headOption
    }
  }

  // All enclosing trees of the given position, after typing
  def typedTreesAt(offset: Int): Either[Throwable, List[global.Tree]] = {
    def treesAtPos(tree: Tree, targetPos: Position): List[Tree] = if (tree.pos.properlyIncludes(targetPos)) {
      tree :: tree.children.collect {
        case t if t.pos.properlyIncludes(targetPos) => treesAtPos(t, targetPos)
      }.flatten
    } else Nil

    for {
      unit <- compileUnit
      tree <- quickTyped
    } yield treesAtPos(tree, Position.offset(unit.source, offset))
  }

  // even if there are incomplete statements/parse errors, we want to be able to ask for completions
  // so this ignores any reported compile errors and returns completion candidates for the given offset position
  def completionsAt(offset: Int, includeOperators: Boolean = false): Either[Throwable, (global.Type, List[global.Symbol])] = {
    def isVisibleSymbol(sym: global.Symbol) =
      sym.isPublic && !sym.isSynthetic && !sym.isConstructor && !sym.isOmittablePrefix && !sym.name.decodedName.containsChar('$')

    def getContext(tree: Tree) = for {
      contextOpt <- withCompiler(global.locateContext(tree.pos))
      context    <- Either.fromOption(contextOpt, new RuntimeException("empty context"))
    } yield context

    def completionResults(tree: Tree): Either[Throwable, (global.Type, List[global.Symbol])] = tree match {
      case global.Select(qual: global.Tree, _) if qual != null =>

        // this brings in completions that are available through implicit enrichments
        def addContext(scope: global.Scope): global.Scope = getContext(tree).flatMap {
          context =>
            val ownerTpe = qual.tpe match {
              case global.MethodType(List(), rtpe) => rtpe
              case _ => qual.tpe
            }
            if (ownerTpe != null) Either.catchNonFatal {
              val allImplicits = new global.analyzer.ImplicitSearch(qual, global.definitions.functionType(List(ownerTpe), global.definitions.AnyTpe), isView = true, context0 = context).allImplicits
              val implicitScope = scope.cloneScope
              allImplicits.foreach {
                result =>
                  val members = result.tree.tpe.finalResultType.members
                  members.foreach(implicitScope.enterIfNew)
              }
              implicitScope
            } else Either.left(NoOwnerType)
        }.right.getOrElse{
          scope
        }

        for {
          tpe     <- withCompiler(qual.tpe)
          widened <- if (tpe != null) withCompiler(tpe.widen) else Right(global.NoType)
          scope    = addContext(widened.members)
          members <- withCompiler(scope.view.filter(isVisibleSymbol).filter(_.isTerm))
        } yield widened -> {
          for {
            sym <- members
            if includeOperators || !sym.name.isOperatorName
          } yield sym
        }.groupBy(_.name.toString).toList.map {
          case (_, syms) => syms.head
        }

      case global.Ident(name: global.Name) =>
        for {
          context    <- getContext(tree)
        } yield global.NoType ->
          (context.scope.filter(_.name.startsWith(name)).toList ++ context.imports.flatMap(_.allImportedSymbols.filter(_.name.startsWith(name))))

      // this works pretty well. Really helps with imports. But is there a way we can index classes & auto-import them like IntelliJ does?
      case global.Import(qual: global.Tree, List(name)) if !qual.isErrorTyped =>
        withCompiler {
          val syms = qual.tpe.members.view  // for imports, provide only the visible symbols, and only distinct names
            .filter(isVisibleSymbol)        // (since imports import all overloads of a name)
            .filter(_.isDefinedInPackage)   // and sort them so that packages come first
            .groupBy(_.name.toString).map(_._2.toSeq.minBy(s => !s.isTerm))
            .toList.sortBy(_.isPackageClass)(Ordering.Boolean.reverse)
          global.NoType -> syms
        }
      case other =>
        // no completions available for that tree
        Right(global.NoType -> Nil)
    }

    for {
      treeOpt <- typedTreeAt(offset)
      results <- treeOpt.fold[Either[Throwable, (global.Type, List[global.Symbol])]](Right(global.NoType -> Nil))(completionResults)
    } yield results
  }


  // returns a list of parameters to the current method, if one is being called, as well as the index of the
  // parameter list the tree is in and the index of the parameter within that list.
  def signatureAt(offset: Int): Either[Throwable, (global.Type, List[global.Symbol], Int, Int)] = {
    def signatureResult(tree: Tree, nthList: Int, prevArgs: Int) = tree match {
      case global.Apply(fn: global.Tree, args: List[global.Tree]) =>
        withCompiler {
          val fnTpe = fn.tpe
          val fnSym = fn.symbol
          val argIndex = args.takeWhile{
            arg =>
              arg.pos.end < offset
          }.size
          (fnTpe, List(fnSym), argIndex + prevArgs, nthList)
        }
      case other => Either.left(new RuntimeException("Not apply tree"))
    }

    @tailrec
    def nestedApplies(tree: Tree, n: Int = -1, a: Int = -1): (Int, Int) = tree match {
      case global.Apply(fun: global.Tree, args) => nestedApplies(fun, n + 1, if (a < 0) 0 else a + args.size)
      case _ => (n, a)
    }

    for {
      trees   <- typedTreesAt(offset)
      firstAp  = trees.reverse.dropWhile(!_.isInstanceOf[global.Apply])
      applyOpt = firstAp.headOption  // deepest Apply tree
      apply   <- Either.fromOption(applyOpt, NoApplyTree)
      (ad, pn) = nestedApplies(apply)  // how many param lists deep are we?
      result  <- signatureResult(apply, ad, pn)
    } yield result
  }

  lazy val directImports: List[global.Tree] = {
    for {
      stats <- parsed
    } yield stats.collect {
      case i @ global.Import(expr, selectors) => global.Import(reassignThis(moduleInstanceRef)(expr), selectors)
    }
  }.right.getOrElse(Nil)

  lazy val compiledModule: Either[Throwable, global.Symbol] = successfulParse.flatMap {
    _ =>
      val run = new global.Run()
      compileUnit.flatMap { unit =>
        withCompiler {
          unit.body = global.resetAttrs(unit.body)
          reporter.attempt(run.compileUnits(List(unit), run.namerPhase))
        }.flatMap(identity).flatMap {
          _ =>
            withCompiler {
              unit.body.asInstanceOf[global.PackageDef].stats(1).symbol.companionModule
            }
        }
      }.leftFlatMap {
        case EmptyCell => Right(global.NoSymbol)
        case err => Left(err)
      }
  }

  lazy val decls = compiledModule.flatMap {
    sym => withCompiler {
      global.exitingTyper(sym.info.decl(global.TermName("INSTANCE")).info.nonPrivateDecls)
    }
  }

  def compile: Either[Throwable, global.Symbol] = {
    val _ = decls
    compiledModule
  }

}

object ScalaSource {

  def apply(
    kernelContext: KernelContext,
    cellContext: CellContext,
    previousSources: List[ScalaSource[_ <: Global]],
    notebookPackage: String,
    code: String,
    prependCode: String = ""
  ): ScalaSource[kernelContext.global.type] = {
    import kernelContext.global
    val reporter = global.reporter.asInstanceOf[KernelReporter]
    val cellName = nameFor(cellContext)
    val unitParser = global.newUnitParser(code, cellName)
    val parsed = reporter.attemptIor(unitParser.parseStats())

    val prependParsed = Option(prependCode).filterNot(_.isEmpty).flatMap {
      prependCode =>
        val prependParser = global.newUnitParser(prependCode)
        reporter.attemptIor(prependParser.parseStats()).toOption
    }

    new ScalaSource[kernelContext.global.type](
      global,
      cellContext,
      previousSources.asInstanceOf[List[ScalaSource[kernelContext.global.type]]],
      notebookPackage,
      parsed,
      prependParsed
    )
  }

  def fromTrees(kernelContext: KernelContext)(cellContext: CellContext, notebookPackage: String, trees: List[kernelContext.global.Tree]): ScalaSource[kernelContext.global.type] = {
    new ScalaSource[kernelContext.global.type](kernelContext.global, cellContext, Nil, notebookPackage, Ior.right(trees))
  }

  private def nameFor(cell: CellContext): String = s"Cell${cell.id}"

}

case object NoApplyTree extends Throwable
case object NoOwnerType extends Throwable