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

      // import everything imported by previous cells...
      val directImports: List[global.Tree] = previousSources.flatMap(_.directImports.asInstanceOf[List[global.Tree]])

      def deepCopyTree(t: Tree): Tree = {
        val treeDuplicator = new global.Transformer {
          // by default Transformers don’t copy trees which haven’t been modified,
          // so we need to use use strictTreeCopier
          override val treeCopy: global.TreeCopier = global.newStrictTreeCopier
        }

        treeDuplicator.transform(t)
      }

      // we deepcopy the trees to make sure we don't mess with the ones we want.
      val throwawayTrees = trees.map(deepCopyTree)
      val throwawayPrepend = prepend.toList.flatten.asInstanceOf[List[global.Tree]].map(deepCopyTree)

      // we don't yet know which cells are used, so import all of them.
      val allImports = previousSources.map {
        source =>
          val moduleSym = source.compiledModule.right.get.asModule
          q"import ${moduleSym.asInstanceOf[global.ModuleSymbol]}.INSTANCE._"
      }

      val x = q"org.apache.spark.sql.catalyst.encoders.OuterScopes.addOuterScope(this)"
      val y = prepend.get.head

      // compile and type the tree
      val packagedTrees = global.PackageDef(
        global.Ident(global.newTermName("ThrowawayPackage")),
          directImports ::: List(global.ClassDef(
            global.Modifiers(),
            global.newTypeName(s"ThrowawayClass"),
            Nil,
            global.Template(
              List(scalaDot(global.typeNames.AnyRef), scalaDot(global.typeNames.Serializable)),
              global.noSelfType,
              constructor :: allImports ::: throwawayPrepend /*::: List(q"org.apache.spark.sql.catalyst.encoders.OuterScopes.addOuterScope(this)")*/ ::: throwawayTrees
            ))))

      val compiledTrees = withCompiler {
        val sourceFile = CellSourceFile("throwaway")
        val unit = new global.RichCompilationUnit(sourceFile)
        unit.body = packagedTrees
        unit.status = global.JustParsed
        global.unitOfFile.put(sourceFile.file, unit)
        unit
//        packagedTrees
      }.right.get

      val typedTrees = withCompiler {
        val run = new global.Run()
        run.namerPhase.asInstanceOf[global.GlobalPhase].apply(compiledTrees)
        run.typerPhase.asInstanceOf[global.GlobalPhase].apply(compiledTrees)
        val x = global.exitingTyper(compiledTrees.body)
//        global.askReset()
        x
      }.right.get

//      val run = new global.Run()

      val prevCellNames = previousSources.map(_.compiledModule.right.get.asModule.name)
      val usedIdents = scala.collection.mutable.HashMap.empty[String, global.Tree]
      val cellRefFinder = new global.Transformer {
        override def transform(tree: global.Tree): global.Tree = tree match {
          case t @ global.Select(global.Select(qualifier: global.Ident, global.TermName("INSTANCE")), name) if prevCellNames.contains(qualifier.name) =>
            usedIdents += name.toString -> t
            super.transform(t)
          case _ => super.transform(tree)
        }
      }
      cellRefFinder.transform(typedTrees)

      //... and also import all public declarations from previous cells
      val impliedImports = previousSources.foldLeft(ListMap.empty[String, (global.ModuleSymbol, global.Symbol)]) {
        (accum, next) =>
          // grab this source's compiled module
          val moduleSym = next.compiledModule.right.get.asModule

          // grab all the non-private members declared in the module
          accum ++ next.decls.right.get.map {
            decl =>
              // Mapping with decl's name to clobber duplicates, keep track of decl Name and the Module it came from
              decl.name.toString -> (moduleSym.asInstanceOf[global.ModuleSymbol], decl.asInstanceOf[global.Symbol])
          }.toMap
        }
        .filter {
          case (nameStr, (module, sym)) => usedIdents contains nameStr
        }
        .toList.groupBy(_._2._1).toList.map {
          case (module, imports) =>
            if (imports.exists(_._2._2.isClass)) { // if there's a class def, we have to just wildcard import.
              val localValName = global.freshTermName(module.name.toString + "$INSTANCE")(global.currentFreshNameCreator)
              val localVal = q"val $localValName = $module.INSTANCE"
              val importLocalVal = q"import $localValName._"
              (List(localVal, importLocalVal), Nil)
            } else { // no class def! We can wildcard import, hooray.
              (Nil, imports.map {
                case (_, (_, sym)) =>
                  val proxyName = global.freshTermName(s"${module.name.toString}$$PROXY$$${sym.name.toString}")(global.currentFreshNameCreator)
                  if (sym.isImplicit) {
                    sym.name.toString -> q"private implicit val $proxyName = $module.INSTANCE.${sym.name.toTermName}"
                  } else {
                    sym.name.toString -> q"private val $proxyName = $module.INSTANCE.${sym.name.toTermName}"
                  }
              })
            }
//            val localValName = global.freshTermName(module.name.toString + "$INSTANCE")(global.currentFreshNameCreator)
//            val localVal = q"val $localValName = $module.INSTANCE"
//            val importLocalVal = q"import $localValName._"
//            (List(localVal, importLocalVal), imports.map {
//              case (_, (_, sym)) =>
//                val proxyName = global.freshTermName(s"${module.name.toString}$$${sym.name.toString}")(global.currentFreshNameCreator)
//                sym.name.toString -> q"val $proxyName = $module.INSTANCE.${sym.name.toTermName}"
//            })
        }

      val (unzippedPrevCellImports, unzippedPrevCellProxies) = impliedImports.unzip
      val prevCellImports = unzippedPrevCellImports.flatten
      val prevCellProxies = unzippedPrevCellProxies.flatten.toMap

      val proxySubstituter = new global.Transformer {
        override def transform(tree: global.Tree): global.Tree = tree match {
          case n @ global.Ident(name) if usedIdents.contains(name.toString) =>
            val x = n
            val replacementIdent = prevCellProxies.get(name.toString) match {
              case Some(global.ValDef(_, proxyName, _, _)) =>
                global.Ident(proxyName.toString)
              case None => tree
            }
            super.transform(replacementIdent)
          case _ =>
            super.transform(tree)
        }
      }

      val modifiedTrees = trees.map(proxySubstituter.transform).map(global.resetAttrs)

      // This gnarly thing is building a synthetic object {} tree around the statements.
      // each expression gets its own object and class (and imports previous expressions)
      // TODO: Not yet done:
      //    * extracting values no longer works (Scala interpreter doesn't know that it needs to go one more level in)
      //    * importing previous cell not yet implemented properly
      // Quasiquotes won't do the trick, because we have to assign positions to every tree or the compiler freaks out.
      // We just smush the positions of the wrapper code to the beginning and end.
      // We don't just make a new code string and re-parse, because we want the real positions in the cell to be
      // reported in any compile errors on the client side.
      // TODO: position validation seems to happen at parser phase – could tell Global to skip that and avoid all of this?
      val wrappedSource = atPos(range) {
        global.PackageDef(
          atPos(beginning)(global.Ident(global.TermName(notebookPackage))),
          directImports.map(forcePos(beginning, _)).map(global.resetAttrs) :::
            List(atPos(beginning) {
              global.ModuleDef(
                global.Modifiers(),
                moduleName,
                atPos(end) {
                  global.Template(
                    List(atPos(end)(scalaDot(global.typeNames.AnyRef))),
                    atPos(end)(global.noSelfType),
                    List(atPos(end)(constructor)) :::
                      modifiedTrees.foldLeft((List.empty[Tree], 0)) {
                        case ((acc, idx), tree) =>
//                          tree match {
//                            case _: global.Import =>  // if its an import, don't give it its own class
//                              (acc :+ tree, idx)
//                            case _ =>
                              val name = s"$moduleName$$tree$idx"
                              (acc ::: List[global.Tree](
                                atPos(range) {
                                  global.ClassDef(
                                    global.Modifiers(),
                                    global.TypeName(name),
                                    Nil,
                                    atPos(range) {
                                      global.Template(
                                        List(atPos(beginning)(scalaDot(global.typeNames.AnyRef)), atPos(beginning)(scalaDot(global.typeNames.Serializable))), // extends AnyRef with Serializable
                                        atPos(beginning)(global.noSelfType),
                                        atPos(beginning)(constructor) ::
                                          prevCellImports.map(atPos(beginning)) :::
                                          prevCellProxies.values.map(atPos(beginning)).toList :::
                                          // need to import previous trees and all imports in previous trees too
                                          {
                                            acc.foldLeft(List.empty[global.Tree]) {
                                              case (a, n) =>
                                                n match {
                                                  case global.ClassDef(_, _, _, global.Template(_, _, body)) =>
                                                    a ::: body.collect {
                                                      case imp: global.Import if !imp.toString.contains("INSTANCE") && !a.contains(imp) =>
                                                        imp
                                                    }
                                                  case m @ global.ModuleDef(_, modName, _) =>
                                                    val x = m
                                                    val y =  q"import $modName.INSTANCE._"
                                                    val z = global.TermName(s"$modName")
                                                    val w = q"import $z.INSTANCE._"
                                                    val localValName = global.freshTermName(modName.toString + "$INSTANCE")(global.currentFreshNameCreator)
                                                    val localVal = q"val $localValName = $modName.INSTANCE"
                                                    val importLocalVal = q"import $localValName._"

                                                    a ::: localVal :: importLocalVal :: Nil
                                                  case _ => a
                                                }
                                            }
                                          } :::

//                                          (0 to idx).flatMap {
//                                            i =>
//                                              val importName = global.TermName(s"$moduleName$$tree$i")
//                                              val importPrev = q"import $importName.INSTANCE._"
//                                              val prevDirectImports = acc() match {
//                                                case imp: global.Import if !imp.toString.contains("INSTANCE") => List(imp)
//                                                case _ => Nil
//                                              }
//                                              importPrev :: prevDirectImports
//                                          }.toList :::
                                          prepend.toList.flatten.asInstanceOf[List[global.Tree]].map(forcePos(beginning, _)) :::
                                          tree :: Nil)
                                    })
                                },
                                atPos(end) {
                                  global.ModuleDef(
                                    global.Modifiers(),
                                    global.TermName(name),
                                    atPos(end) {
                                      global.Template(
                                        List(atPos(end)(scalaDot(global.typeNames.AnyRef))),
                                        atPos(end)(global.noSelfType.copy()),
                                        atPos(end)(constructor) ::
                                          atPos(end) {
                                            val instanceName = global.TypeName(name)
                                            q"val INSTANCE = new $instanceName"
                                          } :: Nil
                                      )
                                    }
                                  )
                                }
                              ) /*::: {
                                val localValName = global.freshTermName(name.toString + "$INSTANCE")(global.currentFreshNameCreator)
                                val instanceName = global.TermName(name)
                                val localVal = q"val $localValName = $instanceName.INSTANCE"
                                val importLocalVal = q"import $localValName._"
                                List(localVal, importLocalVal).map(atPos(end)(_))
                              }*/, idx + 1)
//                          }
                    }._1
                  )
                }
              )
            })
        )
      }
      ensurePositions(
        source,
        wrappedSource,
        range
      )


//      val untypedTrees = global.resetAttrs(typedTrees)   // works!
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
      val _ = compileUnit
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