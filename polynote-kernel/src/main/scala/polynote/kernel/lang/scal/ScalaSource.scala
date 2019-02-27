package polynote.kernel.lang.scal

import cats.data.Ior
import cats.syntax.either._
import polynote.kernel.EmptyCell
import polynote.kernel.util.KernelReporter

import scala.annotation.tailrec
import scala.reflect.internal.util.{ListOfNil, Position, RangePosition, SourceFile}

/**
  * Captures some Scala source in the context of a notebook, and provides compilation operations.
  *
  * TODO: the refined Interpreter type is really annoying; dictated by Global's path-dependent types. Can that be
  *       unwound?
  */
class ScalaSource[Interpreter <: ScalaInterpreter](val interpreter: Interpreter)(
  id: String,
  availableSymbols: Set[Interpreter#Decl],
  previousSources: List[ScalaSource[Interpreter]],
  code: String
) {
  import interpreter.symbolTable.kernelContext.global
  import interpreter.notebookPackage
  import global.{Type, Tree, atPos}

  private val reporter = global.reporter.asInstanceOf[KernelReporter]

  private def withCompiler[A](fn: => A): Either[Throwable, A] = Either.catchNonFatal {
    global.ask(() => fn)
  }

  def moduleRef: global.Select = global.Select(notebookPackage, moduleName)

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

  private lazy val unitParser = global.newUnitParser(code, id)

  // the parsed trees, but only successful if there weren't any parse errors
  lazy val parsed: Ior[Throwable, List[Tree]] = reporter.attemptIor(unitParser.parseStats())

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

  lazy val moduleName: global.TermName = global.TermName(s"Eval$$$id$$$executionId")

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

  lazy val resultName: Option[global.TermName] = results.right.toOption.flatMap(_._1)

  lazy val wrapped: Either[Throwable, Tree] = results.right.map {
    case (_, trees) =>
      val range = new RangePosition(trees.head.pos.source, 0, 0, trees.last.pos.end)
      val beginning = new RangePosition(trees.head.pos.source, 0, 0, 0)
      val end = new RangePosition(trees.last.pos.source, trees.last.pos.end, trees.last.pos.end, trees.last.pos.end)

      import global.Quasiquote

      val constructor = atPos(beginning) {
        global.DefDef(
          global.NoMods,
          global.nme.CONSTRUCTOR,
          Nil,
          ListOfNil,
          atPos(beginning)(global.TypeTree()),
          atPos(beginning)(global.Block(
            atPos(beginning)(global.PrimarySuperCall(ListOfNil)), atPos(beginning)(global.gen.mkSyntheticUnit()))))
      }

      // find all externally-defined values in cells above this one, and make private alias methods for them
      val externalVals = availableSymbols
        .filterNot(_.source contains interpreter)
        .toList.map {
          sym =>
            val name = global.TermName(sym.name)
            val typ = sym.scalaType(global)
            atPos(beginning)(
              global.DefDef(
                global.Modifiers(global.Flag.PRIVATE),
                name,
                Nil,
                Nil,
                global.TypeTree(typ),
                atPos(beginning)(q"_root_.polynote.runtime.Runtime.getValue(${name.toString}).asInstanceOf[$typ]")))
        }

      // import everything imported by previous cells, and also import all public declarations from previous cells
      val imports: List[global.Tree] = previousSources.flatMap {
        source =>
          source.directImports.asInstanceOf[List[global.Tree]] ++ {
            source.compiledModule match {
              case Right(sym) if !(sym eq global.NoSymbol) => List(q"import ${sym.asInstanceOf[global.Symbol]}._")
              case _ => Nil
            }
          }
      }

      // This gnarly thing is building a synthetic object {} tree around the statements.
      // Quasiquotes won't do the trick, because we have to assign positions to every tree or the compiler freaks out.
      // We just smush the positions of the wrapper code to the beginning and end.
      // We don't just make a new code string and re-parse, because we want the real positions in the cell to be
      // reported in any compile errors on the client side.
      // TODO: position validation seems to happen at parser phase – could tell Global to skip that and avoid all of this?
      val wrappedSource = atPos(range) {
        global.PackageDef(
          atPos(beginning)(notebookPackage),
          imports.map(forcePos(beginning, _)).map(global.resetAttrs) ::: List(
            atPos(range) {
              global.ModuleDef(
                global.Modifiers(),
                moduleName,
                atPos(range) {
                  global.Template(
                    List(atPos(beginning)(tq"polynote.runtime.ScalaCell")), // extends AnyRef with Serializable
                    atPos(beginning)(global.noSelfType.copy()),
                    atPos(beginning)(constructor) :: externalVals ::: trees)
                })
            }))
      }
      ensurePositions(
        trees.head.pos.source,
        wrappedSource,
        range
      )

      wrappedSource
  }

  private lazy val compileUnit: Either[Throwable, global.CompilationUnit] = wrapped.right.map {
    tree =>
      val sourceFile = CellSourceFile(id)
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
      case global.Select(qual: global.Tree, _) =>

        // this brings in completions that are available through implicit enrichments
        def addContext(scope: global.Scope): global.Scope = getContext(tree).map {
          context =>
            val ownerTpe = qual.tpe match {
              case global.MethodType(List(), rtpe) => rtpe
              case _ => qual.tpe
            }
            val allImplicits = new global.analyzer.ImplicitSearch(qual, global.definitions.functionType(List(ownerTpe), global.definitions.AnyTpe), isView = true, context0 = context).allImplicits
            val implicitScope = scope.cloneScope
            allImplicits.foreach {
              result =>
                val members = result.tree.tpe.finalResultType.members
                members.foreach(implicitScope.enterIfNew)
            }
            implicitScope
        }.right.getOrElse{
          scope
        }

        for {
          tpe     <- withCompiler(qual.tpe.widen)
          scope    = addContext(tpe.members)
          members <- withCompiler(scope.view.filter(isVisibleSymbol).filter(_.isTerm))
        } yield tpe -> {
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
      tree    <- Either.fromOption(treeOpt, new RuntimeException("No typed tree at position"))
      results <- completionResults(tree)
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
      apply   <- Either.fromOption(applyOpt, new RuntimeException("No apply tree at position"))
      (ad, pn) = nestedApplies(apply)  // how many param lists deep are we?
      result  <- signatureResult(apply, ad, pn)
    } yield result
  }

  lazy val directImports: List[global.Tree] = {
    for {
      stats <- parsed
    } yield stats.collect {
      case i @ global.Import(expr, selectors) => global.Import(reassignThis(moduleRef)(expr), selectors)
    }
  }.right.getOrElse(Nil)

  lazy val compiledModule: Either[Throwable, global.Symbol] = successfulParse.flatMap {
    _ =>
      compileUnit.flatMap { unit =>
        withCompiler {
          global.demandNewCompilerRun()
          val run = new global.Run()
          unit.body = global.resetAttrs(unit.body)
          reporter.attempt(run.compileUnits(List(unit), run.namerPhase))
        }.flatMap(identity).flatMap {
          _ =>
            withCompiler(unit.body.asInstanceOf[global.PackageDef].stats.head.symbol.module)
        }
      }.leftFlatMap {
        case EmptyCell => Right(global.NoSymbol)
        case err => Left(err)
      }
  }

  def compile: Either[Throwable, global.Symbol] = compiledModule

}
