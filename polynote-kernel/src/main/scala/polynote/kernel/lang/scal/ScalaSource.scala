package polynote.kernel.lang.scal

import cats.data.Ior
import cats.syntax.either._
import polynote.config.{PolyLogger, PolynoteConfig}
import polynote.kernel.{EmptyCell, RuntimeError}
import polynote.kernel.util.{CellContext, KernelContext, KernelReporter}

import scala.annotation.tailrec
import scala.collection.immutable.ListMap
import scala.reflect.internal.util.{ListOfNil, Position, RangePosition, SourceFile}
import scala.reflect.internal.ModifierFlags
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

  private val logger = new PolyLogger

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

  private def ensurePositions(tree: Tree): Position = {
    val source = tree.pos.source
    val endPos = tree.pos.end
    val range = new RangePosition(source, 0, 0, endPos)
    ensurePositions(source, tree, range)
  }

  private def forcePos[T <: global.Tree](pos: Position, tree: T): T = {
    val transformer = new global.Transformer {
      override def transform(tree: global.Tree): global.Tree = {
        super.transform(tree).setPos(pos)
      }
    }
    transformer.transform(tree).asInstanceOf[T]
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

  // little helper for deep copying trees
  def deepCopyTree(t: Tree): Tree = {
    val treeDuplicator = new global.Transformer {
      // by default Transformers don’t copy trees which haven’t been modified,
      // so we need to use use strictTreeCopier
      override val treeCopy: global.TreeCopier = global.newStrictTreeCopier
    }

    treeDuplicator.transform(t)
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

      val range = Position.range(source, 0, 0, endPos)
      val beginning = Position.transparent(source, -1, -1, -1)
      val end = Position.transparent(source, endPos, endPos, endPos)

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
      val (directImports: List[global.Tree], localImports: List[global.Tree]) = previousSources.flatMap(_.directImports.asInstanceOf[List[global.Tree]]).partition {
        // basically, differentiates `import org.apache.spark....` from `val spark = ???; import spark.implicits._`
        case global.Import(expr, _) => expr.tpe.prefixChain.last.typeSymbol.hasPackageFlag
      }

      //... and also import all public declarations from previous cells
      val x = previousSources
      val impliedImports = previousSources.foldLeft(ListMap.empty[String, (global.ModuleSymbol, global.Symbol)]) {
        (accum, next) =>
          // grab this source's compiled module
          val moduleSym = next.compiledModule.right.get.asModule

          // grab all the non-private, non-constructor members declared in the module
          accum ++ next.decls.right.get.filterNot(_.isConstructor).map {
            decl =>
              // Mapping with decl's name to clobber duplicates, keep track of decl Name and the Module it came from
              decl.name.toString -> (moduleSym.asInstanceOf[global.ModuleSymbol], decl.asInstanceOf[global.Symbol])
          }.toMap
        }
        .toList.groupBy(_._2._1).flatMap {
          case (module, imports) =>
            imports.map {
              case (_, (_, sym)) =>
                q"import ${module.name}.INSTANCE.${sym.name}"
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
          directImports.map(forcePos(beginning, _)).map(global.resetAttrs) :::
          List(
            atPos(range) {
              global.ClassDef(
                global.Modifiers(),
                moduleName.toTypeName,
                Nil,
                atPos(range) {
                  global.Template(
                    List(atPos(beginning)(scalaDot(global.typeNames.AnyRef)), atPos(beginning)(scalaDot(global.typeNames.Serializable))), // extends AnyRef with Serializable
                    atPos(beginning)(global.noSelfType),
                    forcePos(beginning, constructor) ::
                      impliedImports.map(atPos(beginning)) :::
                      localImports.map(forcePos(beginning, _)).map(global.resetAttrs) :::
                      prepend.toList.flatten.asInstanceOf[List[global.Tree]].map(forcePos(beginning, _)) :::
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
                    atPos(end)(global.noSelfType),
                    forcePos(end, constructor) :: atPos(end)(q"val INSTANCE = new ${moduleName.toTypeName}") :: Nil
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

  private lazy val compileUnit: Either[Throwable, global.RichCompilationUnit] = wrapped.right.map {
    tree =>
      val sourceFile = CellSourceFile(cellName)
      val unit = new global.RichCompilationUnit(sourceFile) //new global.CompilationUnit(CellSourceFile(id))
      unit.body = tree
      unit.status = global.JustParsed
      global.unitOfFile.put(sourceFile.file, unit)
      unit
  }

  // compiled unit but this time with all our fancy substitutions to help prevent serialization issues
  lazy val compileUnitWithSubstitutions: Either[Throwable, global.RichCompilationUnit] = compileUnit.right.flatMap {
    unit =>
      import global.Quasiquote

      logger.debug(s"*********** START pre-processed cell ${moduleName} ********** ")
      logger.debug(unit.body.toString)
      logger.debug(s"*********** END pre-processed cell ${moduleName} ********** ")

      Either.catchNonFatal {
        // first step is lifting up all class definitions and their companion objects (if present) to the package level
        // this makes things easier because then there are no inner classes around and we can make sure users can't
        // close over the whole world in their classes.

        // so, we'll first collect the names of the classes so we can find companion objects
        val classes: Map[String, global.Tree] = unit.body match {
          case global.PackageDef(_, stats) =>
            stats.collect {
              case global.ClassDef(_, name, _, tmpl) if name.toString == moduleName.toString =>
                tmpl match {
                  case global.Template(_, _, body) =>
                    body.collect {
                      case c: global.ClassDef => c.name.toString -> c
                    }
                }
            }.flatten.toMap
        }

        // and now we can find companion objects
        val companionObjects: Map[String, global.Tree] = unit.body match {
          case global.PackageDef(_, stats) =>
            stats.collect {
              case global.ClassDef(_, name, _, tmpl) if name.toString == moduleName.toString =>
                tmpl match {
                  case global.Template(_, _, body) =>
                    body.collect {
                      case o: global.ModuleDef if classes.contains(o.name.toString) => o.name.toString -> o
                    }
                }
            }.flatten.toMap
        }

        // ok, now we know what we're dealing with! Let's lift these guys up to the package level.
        val liftedTree = unit.body match {
          case pkg @ global.PackageDef(_, stats) =>
            pkg.copy(stats = stats.flatMap {
              case cls @ global.ClassDef(_, name, _, tmpl) if name.toString == moduleName.toString =>
                // remove the unlifted classes and objects
                val newClass = cls.copy(impl = tmpl match {
                  case global.Template(_, _, body) =>
                    tmpl.copy(body = body.filter {
                      // all classdefs have been accounted for
                      case _: global.ClassDef => false
                      // only remove companion objects
                      case global.ModuleDef(_, n, _) => !companionObjects.contains(n.toString)
                      // the rest is ok
                      case _ => true
                    }).setPos(tmpl.pos)
                }).setPos(cls.pos)

                // insert the classes and their companion objects. They go before the cell class definition.
                val lifted: List[global.Tree] = classes.flatMap {
                  case (n, c) =>
                    c :: companionObjects.get(n).toList
                }.map(forcePos(new RangePosition(cls.pos.source, cls.pos.start, cls.pos.start, cls.pos.start), _)).toList

                lifted :+ newClass

              case other => List(other)
            }).setPos(pkg.pos)
        }

        unit.body = liftedTree
        global.unitOfFile.put(unit.source.file, unit) // make sure to update

        // so there's one more thing we need to consider. It turns out that some output from the typechecker can't be
        // reversed, such as case classes and lazy vals, which can't handle being put through the typer twice (and we
        // can't roll back the changes that the typer does to them...) so, what we'll do here is copy 'em before we type
        // the trees. later, we'll substitute the typed trees for the fresh, untyped ones so they can go through
        // the typer again without causing problems.

        // first, case classes. (we have to do the same thing for their companion objects as well).
        val caseClasses = classes.collect {
          case (k, cls: global.ClassDef) if cls.mods.isCase => k -> deepCopyTree(cls)
        }
        // we also need to keep track of any user-defined companion objects there might be, so that we can substitute them too.
        val caseClassCompanionObjects = companionObjects.collect {
          case (k, o: global.ModuleDef) if caseClasses.contains(o.name.toString) => k -> deepCopyTree(o)
        }

        // now, lazy vals
        val lazyVals = liftedTree match {
          case global.PackageDef(_, stats) =>
            stats.collect {
              case global.ClassDef(_, _, _, impl) =>
                impl.body.collect {
                  case v @ global.ValDef(mods, _, _, _) if mods.hasFlag(ModifierFlags.LAZY) =>
                    v.name.toString -> v
                }
            }.flatten.toMap
        }

        // type the tree so we can reify implicits and all that nice stuff.
        val typedPkg = reporter.attempt {
          val run = new global.Run()
          global.globalPhase = run.namerPhase // make sure globalPhase matches run phase
          run.namerPhase.asInstanceOf[global.GlobalPhase].apply(unit)
          global.globalPhase = run.typerPhase // make sure globalPhase matches run phase
          run.typerPhase.asInstanceOf[global.GlobalPhase].apply(unit)
          global.exitingTyper(unit.body)
        } match {
          case Right(t) => t
          case Left(err) => throw err
        }

        // now that we've typed it all up we can look for references to previous cells.
        val prevCellNames = previousSources.map(_.compiledModule.right.get.asModule.name)
        val usedIdents = typedPkg.collect {
          case t @ global.Select(global.Select(qualifier: global.Ident, global.TermName("INSTANCE")), name) if prevCellNames.contains(qualifier.name) =>
            name.toString -> t
        }.toMap

        // now, find all referenced decls from previous cells and generate proper imports and proxy variables (to avoid closing over the whole cell if possible)
        val importAndProxyFinder = previousSources.foldLeft(ListMap.empty[String, (global.ModuleSymbol, global.Symbol)]) {
          (accum, next) =>
            // grab this source's compiled module
            val moduleSym = next.compiledModule.right.get.asModule

            // grab all the non-private members declared in the module
            accum ++ next.decls.right.get.map {
              decl =>
                // Mapping with decl's name to clobber duplicates, keep track of decl Name and the Module it came from
                decl.name.toString -> (moduleSym.asInstanceOf[global.ModuleSymbol], decl.asInstanceOf[global.Symbol])
            }.toMap
        }.filter {
          case (nameStr, (_, _)) => usedIdents contains nameStr
        }.groupBy(_._2._1).toList.map {
          case (module, imports) =>

            // for each module, we need to generate:
            //   1. new imports and proxy variable definitions
            //   2. substitutions for existing variables

            // lazy to avoid needlessly allocating a fresh term name unless we need to
            lazy val moduleProxyName = global.freshTermName(module.name.toString + "$PROXY$INSTANCE")(global.currentFreshNameCreator)
            lazy val moduleProxy = q"private val $moduleProxyName = $module.INSTANCE"

            // we only want to insert the module proxy if it's needed. We'd rather not if we can help it, because that
            // way we avoid closing over that entire cell.
            def getSymFromModuleProxy(sym: global.Symbol): Boolean =
              (
                sym.isSourceMethod // make sure it's a real method and not synthetic
                  || sym.isClass   // if it's a class we need the module proxy
              ) && !sym.isAccessor // if it's just an accessor we don't need the proxy (surprised that isSourceMethod is true for these actually).

            val moduleProxyNeeded = imports.exists {
              case (_, (_, sym)) => getSymFromModuleProxy(sym)
            }

            val maybeModuleProxy = if (moduleProxyNeeded) List(moduleProxy) else Nil

            val (newTrees, substitutions) = imports.toList.map {
              case (_, (_, sym)) =>
                if (getSymFromModuleProxy(sym)) { // if it's a method/class, we'll rewrite that reference to point to the proxied
                  (Nil, sym.name.toString -> q"$moduleProxyName.${sym.name.toTermName}")
                } else { // otherwise, try to proxy it directly
                  val proxyName = global.freshTermName(s"${module.name.toString}$$PROXY$$${sym.name.toString}$$")(global.currentFreshNameCreator)
                  (List(q"private val $proxyName = $module.INSTANCE.${sym.name.toTermName}"), sym.name.toString -> global.Ident(proxyName.toString))
                }
            }.unzip

            (maybeModuleProxy ::: newTrees.flatten, substitutions.toMap)
        }

        val (unzippedNewTrees, unzippedSubstitutions) = importAndProxyFinder.unzip
        val newProxyTrees = unzippedNewTrees.flatten
        val proxySubstitutions = unzippedSubstitutions.flatten.toMap

        // we replace references to previous cells with a reference to the proxy name instead.
        val substituter = new global.Transformer {
          override def transform(tree: global.Tree): global.Tree = tree match {
            case s @ global.Select(global.Select(_, global.TermName("INSTANCE")), name) if usedIdents.contains(name.toString) =>
              val replacement = proxySubstitutions.get(name.toString) match {
                case Some(s: global.Select) =>
                  forcePos(tree.pos, s)
                case Some(global.Ident(n)) =>
                  forcePos(tree.pos, q"${n.toTermName}")
                case _ => tree
              }
              super.transform(replacement)

            case _ =>
              super.transform(tree)
          }
        }
        val proxiedPkg = substituter.transform(typedPkg)

        // ok, now we need to do some cleanup.
        //   * remove the cell imports we added in the wrapping stage and replace them with our proxies.
        //   * replace the typed case class definitions with the original, fresh defs
        //   * remove the auto-generated companion object (if there was a user-defined companion object, we replace it)
        //   * replace the auto-generated lazy val accessor with our 'fixed' lazy val def
        // the whole thing is a little ugly because we lose position information when we use copy()
        val cleanedPkg = proxiedPkg match {
          case pkg @ global.PackageDef(_, stats) =>
            pkg.copy(stats = stats.flatMap {
              case cls @ global.ClassDef(_, name, _, tmpl) if name.toString.trim == moduleName.toString.trim =>
                List(cls.copy(impl = tmpl match {
                  case global.Template(_, _, body) =>
                    val cleanedBody = body.flatMap {
                      // remove cell imports we added during the wrapping stage
                      case global.Import(global.Select(_, global.TermName("INSTANCE")), _) => Nil
                      // throw away the lazy val def because we will substitute its accessor
                      case global.ValDef(_, n, _, _) if lazyVals.contains(n.toString.trim) => Nil
                      // substitute the lazy val accessor with the untyped val def, but make sure to grab the (possible) substituted rhs
                      case global.DefDef(_, n, _, _, _, global.Block(List(global.Assign(_, rhs)), _)) if lazyVals.contains(n.toString) =>
                        val clean = lazyVals(n.toString).copy(rhs = rhs)
                        List(clean)
                      case other => List(other)
                    }
                    tmpl.copy(body = newProxyTrees ++ cleanedBody).setPos(tmpl.pos)
                }).setPos(cls.pos))

              // replace case classes
              case caseCls @ global.ClassDef(mods, name, _, _) if mods.isCase =>
                List(caseClasses(name.toString))

              // remove or (if user-defined) replace companion objects
              case o @ global.ModuleDef(mods, name, _) if caseClasses.isDefinedAt(name.toString) =>
                caseClassCompanionObjects.get(name.toString).toList

              case other => List(other)
            }).setPos(pkg.pos)
        }

        // we manipulated the tree a bunch, so let's run this again.
        val positionedPkg = global.resetAttrs(cleanedPkg)
        ensurePositions(positionedPkg)
        val repositionedPkg = global.resetAttrs(positionedPkg) // for some reason we need to do this again...

        unit.body = repositionedPkg // repositionedPkg

        logger.debug(s"*********** START post-processed cell ${moduleName} ********** ")
        logger.debug(unit.body.toString)
        logger.debug(s"*********** END post-processed cell ${moduleName} ********** ")

        unit
      }
  }

  // just type the tree – doesn't advance to subsequent phases (for completions etc)
  lazy val quickTyped: Either[Throwable, global.Tree] = for {
    unit <- compileUnit
    tree <- Either.catchNonFatal {
      val run = new global.Run()
      global.globalPhase = run.namerPhase // make sure globalPhase matches run phase
      run.namerPhase.asInstanceOf[global.GlobalPhase].apply(unit)
      global.globalPhase = run.typerPhase // make sure globalPhase matches run phase
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
      val trees = treesAtPos(tree, Position.offset(unit.source, offset)).filter(_.pos.isOpaqueRange)
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
          results    <- results
        } yield {

          val imports = context.imports.flatMap {
            importInfo => importInfo.allImportedSymbols.filter(sym => !sym.isImplicit && sym.isPublic && sym.name.startsWith(name))
          }

          val enclScope = context.enclClass.enclosingContextChain.dropWhile(!_.scope.exists(_ != null)).headOption

          val fromScopes = enclScope.toList.flatMap {
            c => c.scope.filter(sym => sym.name.startsWith(name) && !sym.decodedName.contains('$'))
          }.distinct

          global.NoType ->
            (context.scope.filter(_.name.startsWith(name)).toList
              ++ results._2.filter(_.symbol != global.NoSymbol).map(_.symbol)
              ++ fromScopes
              ++ imports)

        }

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
    } yield (results._1, results._2.distinct)
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
      compileUnitWithSubstitutions.flatMap { unit =>
        val run = new global.Run()
        withCompiler {
          unit.body = global.resetAttrs(unit.body)
          reporter.attempt(run.compileUnits(List(unit), run.namerPhase))
        }.flatMap(identity).flatMap {
          _ =>
            withCompiler {
              // we return the companion object of the cell class for inspection by the scala interpreter.
              val companion: global.ClassDef = unit.body.asInstanceOf[global.PackageDef].stats.collectFirst[global.ClassDef] {
                case cls @ global.ClassDef(_, name, _, _) if cls.symbol.isModuleClass && name.toString == moduleName.toString => cls
              }.getOrElse(throw RuntimeError(new Exception(s"Unable to find companion object for $moduleName!!! Something is very wrong!")))

              companion.symbol.companionModule
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