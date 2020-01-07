package polynote.kernel

import java.io.File
import java.net.URL

import cats.syntax.traverse._
import cats.instances.list._
import polynote.kernel.environment.Config
import polynote.kernel.util.{KernelReporter, LimitedSharingClassLoader, pathOf}
import zio.blocking.Blocking
import zio.system.{System, env}
import zio.internal.{ExecutionMetrics, Executor}
import zio.{Task, RIO, ZIO}
import zio.interop.catz._

import scala.collection.mutable
import scala.reflect.internal.util.{AbstractFileClassLoader, NoSourceFile, Position, SourceFile}
import scala.reflect.io.VirtualDirectory
import scala.reflect.runtime.universe
import scala.tools.nsc.Settings
import scala.tools.nsc.interactive.Global
import scala.reflect.internal.util.ScalaClassLoader.URLClassLoader

import ScalaCompiler.OriginalPos

class ScalaCompiler private (
  val global: Global,
  val notebookPackage: String,
  val classLoader: Task[AbstractFileClassLoader],
  val dependencies: List[File],
  val otherClasspath: List[File]
) {
  import global._
  private val packageName = TermName(notebookPackage)
  private val reporter = global.reporter.asInstanceOf[KernelReporter]

  private val compilerThread: Executor = new Executor {
    def yieldOpCount: Int = Int.MaxValue
    def metrics: Option[ExecutionMetrics] = None
    def submit(runnable: Runnable): Boolean = {
      global.ask(runnable.run)
      true
    }
    def here: Boolean = false
  }

  private def formatTypeInternal(typ: Type): String = typ match {
    case mt @ MethodType(params: List[Symbol], result: Type) =>
      val paramStr = params.map {
        sym => s"${sym.nameString}: ${formatTypeInternal(sym.typeSignatureIn(mt))}"
      }.mkString(", ")
      val resultType = formatTypeInternal(result)
      s"($paramStr) => $resultType"
    case ConstantType(Constant(v)) => v.toString
    case NoType => "<Unknown>"
    case typ if typ.typeSymbol.name == "TypedPythonObject" =>
      typ.typeArgs.headOption.fold("PythonObject")(name => formatTypeInternal(name) + " (Python)")
    case _ =>
      val typName = typ.typeSymbolDirect.rawname
      val typNameStr = typName.decoded
      typ.typeArgs.map(formatTypeInternal) match {
        case Nil => typNameStr
        case a if typNameStr == "<byname>" => s"=> $a"
        case a :: b :: Nil if typName.isOperatorName => s"$a $typNameStr $b"
        case a :: b :: Nil if typ.typeSymbol.owner.nameString == "scala" && (typNameStr == "Function1") =>
          s"$a => $b"
        case args if typ.typeSymbol.owner.nameString == "scala" && (typNameStr startsWith "Function") =>
          s"(${args.dropRight(1).mkString(",")}) => ${args.last}"
        case args => s"$typName[${args.mkString(", ")}]"
      }
  }

  private object saveOriginalPos extends Traverser {
    override def traverse(tree: Tree): Unit = {
      tree.updateAttachment(OriginalPos(tree.pos))
      super.traverse(tree)
    }
  }

  private[kernel] def unsafeFormatType(typ: Type): String = formatTypeInternal(typ)

  private val runtimeMirror = ZIO.accessM[ClassLoader](cl => ZIO(scala.reflect.runtime.universe.runtimeMirror(cl)))
    .memoize.flatten
    .provideSomeM(classLoader)

  private val importer: global.Importer { val from: scala.reflect.runtime.universe.type } = global.mkImporter(scala.reflect.runtime.universe)

  def importType[T : scala.reflect.runtime.universe.TypeTag]: Type = importer.importType(scala.reflect.runtime.universe.typeOf[T])

  def reflect(value: Any): ZIO[Any, Throwable, universe.InstanceMirror] = runtimeMirror.flatMap {
    mirror => ZIO(mirror.reflect(value))
  }


  def formatType(typ: Type): RIO[Blocking, String] =
    zio.blocking.effectBlocking(formatTypeInternal(typ)).lock(compilerThread)

  def formatTypes(types: List[Type]): RIO[Blocking, List[String]] =
    zio.blocking.effectBlocking(types.map(formatTypeInternal)).lock(compilerThread)

  def compileCell(
    cellCode: CellCode
  ): RIO[Blocking, Option[Class[_]]] =
    for {
      compiled  <- cellCode.compile()
      className  = s"${packageName.encodedName.toString}.${cellCode.assignedTypeName.encodedName.toString}"
      cl        <- classLoader
      loadClass  = zio.blocking.effectBlocking(Option(Class.forName(className, false, cl).asInstanceOf[Class[AnyRef]]))
      cls       <- if (cellCode.cellClassSymbol.nonEmpty) loadClass else ZIO.succeed(None)
    } yield cls

  private def parse(unit: CompilationUnit, strictParse: Boolean, packageCell: Boolean) = {
    def parseList(): List[Tree] = if (packageCell) List(newUnitParser(unit).parse()) else newUnitParser(unit).parseStats()
    if (strictParse) ZIO(reporter.attempt(parseList())).absolve else ZIO(parseList())
  }

  def cellCode(
    name: String,
    code: String,
    priorCells: List[CellCode] = Nil,
    inputs: List[ValDef] = Nil,
    inheritedImports: Imports = Imports(),
    strictParse: Boolean = true
  ): Task[CellCode] = for {
    sourceFile  <- ZIO(newSourceFile(code, name))
    compileUnit <- ZIO(new global.RichCompilationUnit(sourceFile))
    parsed      <- parse(compileUnit, strictParse, code.trim().startsWith("package"))
  } yield {
    val definedTerms = parsed.collect {
      case tree: DefTree if tree.name.isTermName => tree.name.decoded
    }.toSet

    val (allowedPriorCells, allowedInputs) = parsed match {
      case PackageDef(_, _) :: Nil => (Nil, Nil)
      case _ => (priorCells, inputs.filterNot(definedTerms contains _.name.decoded))
    }

    CellCode(
      name, parsed, allowedPriorCells, allowedInputs, inheritedImports, compileUnit, sourceFile
    )
  }

  // TODO: currently, there's no caching of the implicits, because what if you define a new implicit? Should we cache?
  def inferImplicits(types: List[Type]): RIO[Blocking, List[Option[AnyRef]]] = {
    val (names, trees) = types.map {
      typ =>
        val name = freshTermName("anon$")(globalFreshNameCreator)
        name.encodedName.toString -> q"val $name: ${TypeTree(typ)} = implicitly[${TypeTree(typ)}]"
    }.unzip

    val cellName = "anonImplicits"

    def construct: RIO[Class[_], AnyRef] =
      ZIO.accessM[Class[_]](cls => ZIO(cls.getDeclaredConstructors.head.newInstance().asInstanceOf[AnyRef]))

    def getField(name: String)(instance: AnyRef): RIO[Class[_], Option[AnyRef]] =
      ZIO.accessM[Class[_]](cls => ZIO(Option(cls.getDeclaredMethod(name).invoke(instance))) orElse ZIO.succeed(None))

    def getFields(names: List[String])(instance: AnyRef): RIO[Class[_], List[Option[AnyRef]]] =
      names.map(getField(_)(instance)).sequence

    // first we'll try to get all of them at once.
    compileCell(CellCode(cellName, trees)).flatMap {
      case Some(cls) => (construct >>= getFields(names)).provide(cls)
      case None => ZIO.fail(new IllegalStateException("Compiler provided no class for implicit results"))
    }.catchAll {
      err =>
        // if that doesn't compile (i.e. some implicits are missing) we'll try to compile each individually
        trees.zip(names).map {
          case (tree, name) =>
            compileCell(CellCode(cellName, List(tree))).flatMap {
              case Some(cls) => (construct >>= getField(name)).provide(cls)
              case None => ZIO.fail(new IllegalStateException("Compiler provided no class for implicit results"))
            }.catchAllCause {
              cause =>
                // TODO: log it?
                ZIO.succeed(None)
            }
        }.sequence
    }
  }

  private def copyAndReset[T <: Tree](tree: T): T = resetAttrs(tree.duplicate.setPos(NoPosition)).asInstanceOf[T]
  private def copyAndReset[T <: Tree](trees: List[T]): List[T] = trees.map(tree => copyAndReset(tree))

  private def template(stats: List[Tree]): Template = Template(
    List(treeBuilder.scalaDot(typeNames.AnyRef)), noSelfType, stats
  )

  // the default implementation of atPos does some additional junk that messes up things (and isn't threadsafe or nesting-safe)
  private def atPos[T <: Tree](pos: Position)(tree: T): T = {
    if (tree.pos == null || !tree.pos.isDefined) {
      tree.setPos(pos)
    }
    tree.children.foreach(atPos(tree.pos))
    tree
  }

  case class CellCode private[ScalaCompiler] (
    name: String,
    code: List[Tree],
    priorCells: List[CellCode] = Nil,
    inputs: List[ValDef] = Nil,
    inheritedImports: Imports = Imports(),
    compilationUnit: RichCompilationUnit = new global.RichCompilationUnit(NoSourceFile),
    sourceFile: SourceFile = NoSourceFile
  ) {

    // this copy of the code will be mutated by compile
    lazy val compiledCode: List[Tree] = code.foldLeft(List.empty[Tree]) {
      (accum, stat) =>
        val copied = duplicateAndKeepPositions(stat)
        if (copied.pos != null && copied.pos.isDefined) {
          saveOriginalPos.traverse(copied)
        } else {
          val pos = accum.headOption.map(_.pos.focusEnd.makeTransparent).getOrElse(beforePos.makeTransparent)
          atPos(pos)(copied)
        }
        copied :: accum
    }.reverse


    // The name of the class (and its companion object, in case one is needed)
    lazy val assignedTypeName: TypeName = freshTypeName(s"$name$$")(global.globalFreshNameCreator)
    lazy val assignedTermName: TermName = assignedTypeName.toTermName

    private lazy val priorCellNames = priorCells.map(_.assignedTypeName)

    // create constructor parameters to hold instances of prior cells; these are needed to access path-dependent types
    // for any classes, traits, type aliases, etc defined by previous cells
    lazy val priorCellInputs: List[ValDef] = priorCells.flatMap {
      cell =>
        cell.cellInstType.toList.map {
          cellInstType => ValDef(Modifiers(), TermName(s"_input${cell.assignedTypeName.decodedName.toString}"), TypeTree(cellInstType).setType(cellInstType), EmptyTree)
        }
    }

    lazy val typedInputs: List[ValDef] = inputs

    // Separate the implicit inputs, since they must be in their own parameter list
    lazy val (implicitInputs: List[ValDef], nonImplicitInputs: List[ValDef]) = typedInputs.partition(_.mods.isImplicit)

    // a position to encompass the whole synthetic tree
    private val end = math.max(0, sourceFile.length - 1)
    private lazy val wrappedPos = Position.range(sourceFile, 0, 0, end)

    // positions for imports mustn't be transparent, or they won't be added to context
    // so we should try to place everything at opaque positions
    private lazy val beforePos = Position.range(sourceFile, 0, 0, 0)
    private lazy val afterPos = Position.range(sourceFile, end, end, end)

    // create imports for all types and methods defined by previous cells
    lazy val priorCellImports: List[Import] = priorCells.zip(priorCellInputs).foldLeft(Map.empty[String, (TermName, Name)]) {
      case (accum, (cell, input)) =>
        val newNames = cell.definedTypesAndMethods.collect {
          case name if !accum.contains(name.decoded) => name
        }
        accum ++ newNames.map(name => name.decoded -> (input.name, name)).toMap
    }.values.groupBy(_._1).toList.map {
      case (input, imports) =>
        val importSelectors = imports.map(_._2).zipWithIndex.map {
          case (name, index) => ImportSelector(name, index, name, index)
        }.toList
        Import(Ident(input), importSelectors)
    }

    // what output values does this code define?
    lazy val outputs: List[ValDef] = code.collect {
      case valDef: ValDef if valDef.mods.isPublic => duplicateAndKeepPositions(valDef).asInstanceOf[ValDef]
    }

    lazy val typedOutputs: List[ValDef] = {
      val prev = outputs.map(v => v.name -> v).toMap

      def notUnit(tpe: Type) = {
        val notNullType = tpe != null
        // val notUnitType = !(tpe <:< typeOf[Unit])
        // val notBoxedUnitType = !(tpe <:< typeOf[BoxedUnit])
        // for some reason the type comparisons sometimes return false positives! WHAT?
        val notUnitType = tpe.typeSymbol.name.decoded != "Unit"
        val notBoxedUnitType = tpe.typeSymbol.name.decoded != "BoxedUnit"
        notNullType && notUnitType && notBoxedUnitType
      }

      // We have to deal with dependent types. If a cell declares a class, and another cell creates a value of that
      // class, it will be typed as i.e. Cell2.this._inputCell1.Foo. This will ordinarily be OK, but if we try
      // to ascribe a type to it, like `foo: Foo` inside of Cell3, this will break because here `Foo` means
      // `Cell3.this._inputCell1.Foo`. So the solution is that we create a fake value of each cell type in its
      // companion, which is never used and is just null. *But*, we target all the values of a cell-defined type
      // to be dependent on that dummy value's singleton type, so that the types will agree everywhere. And when
      // a class takes a previous cell as input, we also type that input as the singleton type. It is a bit odd, but
      // it works at runtime because the dummy value (as a *term*) is never actually referenced.
      val mySym = cellClassSymbol
      val myType = cellClassType
      val dependentTypeMapping = priorCellInputs.zip(priorCells).flatMap {
        case (input, cell) => cell.cellInstType.toList.map(input.name.toString -> _)
      }.toMap

      def transformType(typ: Type): Type = typ match {
        case TypeRef(pre, sym, Nil) =>
          typeRef(transformType(pre), sym, Nil)
        case TypeRef(pre, sym, args) =>
          typeRef(transformType(pre), sym, args.mapConserve(transformType))
        case SingleType(pre, sym) if mySym.contains(pre.typeSymbol) && (dependentTypeMapping contains sym.name.toString) =>
          global.internal.singleType(dependentTypeMapping(sym.name.toString), sym)
        case SingleType(pre, sym) =>
          global.internal.singleType(transformType(pre), sym)
        case typ if myType contains typ =>
          cellInstType.getOrElse(typ)
        case _ =>
          typ
      }

      exitingTyper(compiledCode).collect {
        case vd@ValDef(_, name, tpt, _) if (prev contains name) && notUnit(vd.symbol.originalInfo) =>
          val tpe = vd.symbol.originalInfo
          val preTyper = prev(name)
          val subst = transformType(tpe)
          val typeTree = TypeTree(subst)
          typeTree.setType(subst)
          ValDef(preTyper.mods, preTyper.name, typeTree, EmptyTree).setPos(preTyper.pos)
      }
    }

    // what things does this code import?
    lazy val imports: List[Import] = code.collect {
      case i: Import => i.duplicate
    }

    lazy val compiledImports: List[Import] = compiledCode.collect {
      case i: Import => i.duplicate
    }

    // what types (classes, traits, objects, type aliases) and methods does this code define?
    lazy val definedTypesAndMethods: List[Name] = code.collect {
      case ModuleDef(mods, name, _) if mods.isPublic => name
      case ClassDef(mods, name, _, _) if mods.isPublic => name.toTermName
      case TypeDef(mods, name, _, _) if mods.isPublic => name.toTermName
      case DefDef(mods, name, _, _, _, _) if mods.isPublic => name
    }.distinct

    lazy val wrappedImports = copyAndReset(inheritedImports.externalImports) ++ copyAndReset(inheritedImports.localImports)

    // The code all wrapped up in a class definition, with constructor arguments for the given prior cells and inputs
    private lazy val wrappedClass: ClassDef = {
      val transparentBefore = beforePos.makeTransparent

      def toParam(param: ValDef) = atPos(transparentBefore)(param.copy(mods = param.mods | Flag.PARAMACCESSOR))
      val priorCellParamList = copyAndReset(priorCellInputs).map(toParam)
      val nonImplicitParamList = copyAndReset(nonImplicitInputs).map(toParam)
      val implicitParamList = copyAndReset(implicitInputs).map(toParam)

      // have to do this manually rather than with a quasiquote; latter messes up positions
      val cls = gen.mkClassDef(
        Modifiers(),
        assignedTypeName,
        Nil,
        gen.mkTemplate(
          List(atPos(transparentBefore)(gen.scalaDot(tpnme.Serializable))),
          noSelfType.setPos(transparentBefore),
          Modifiers(),
          List(priorCellParamList, nonImplicitParamList, implicitParamList).filter(_.nonEmpty),
          (priorCellImports ++ wrappedImports).map(atPos(beforePos.makeTransparent)) ++ compiledCode,
          wrappedPos
        )).setPos(wrappedPos)

      /*
        // equivalent tree:
          q"""final class $assignedTypeName(..$priorCellParamList)(..$nonImplicitParamList)(..$implicitParamList) extends ${atPos(transparentBefore)(gen.scalaDot(tpnme.Serializable))} {
            ..${priorCellImports.map(atPos(beforePos))}
            ..${wrappedImports.map(atPos(beforePos))}
            ..${compiledCode}
          }""".setPos(wrappedPos)
       */

      cls
    }

    private lazy val companion: ModuleDef = {
      q"""object $assignedTermName extends {
           final val instance: $assignedTypeName = null
           type Inst = instance.type
         }"""
    }

    // Wrap the code in a class within the given package. Constructing the class runs the code.
    // The constructor parameters are
    private lazy val wrapped: PackageDef = compiledCode match {
      case (pkg @ PackageDef(_, stats)) :: Nil => atPos(wrappedPos)(pkg)
      case code => atPos(wrappedPos) {
        q"""package ${atPos(beforePos)(Ident(packageName))} {
            $wrappedClass
            ${atPos(afterPos)(companion)}
          }"""
      }
    }

    // the type representing this cell's class. It may be null or NoType if invoked before compile is done!
    private def ifNotPackage[T](t: => T): Option[T] = code match {
      case PackageDef(_, _) :: Nil => None
      case _ => Option(t)
    }

    lazy val cellClassType: Option[Type] = ifNotPackage(exitingTyper(wrappedClass.symbol.info))
    lazy val cellClassSymbol: Option[ClassSymbol] = ifNotPackage(exitingTyper(wrappedClass.symbol.asClass))
    lazy val cellCompanionSymbol: Option[ModuleSymbol] = ifNotPackage(exitingTyper(companion.symbol.asModule))
    lazy val cellInstSymbol: Option[Symbol] = cellCompanionSymbol.map(sym => exitingTyper(sym.info.member(TermName("instance")).accessedOrSelf))
    lazy val cellInstType: Option[Type] = cellCompanionSymbol.map(sym => exitingTyper(sym.info.member(TypeName("Inst")).info.dealias))

    // Note – you mustn't typecheck and then compile the same instance; those trees are done for. Instead, make a copy
    // of this CellCode and typecheck that if you need info about the typed trees without compiling all the way
    private[kernel] lazy val typed = {
      val run = new Run()
      compilationUnit.body = wrapped
      compilationUnit.lastBody = wrapped
      unitOfFile.put(sourceFile.file, compilationUnit)
      global.globalPhase = run.namerPhase // make sure globalPhase matches run phase
      run.namerPhase.asInstanceOf[global.GlobalPhase].apply(compilationUnit)
      global.globalPhase = run.typerPhase // make sure globalPhase matches run phase
      run.typerPhase.asInstanceOf[global.GlobalPhase].apply(compilationUnit)
      exitingTyper(compilationUnit.body)
    }

    private[ScalaCompiler] def compile() = ZIO {
      val run = new Run()
      compilationUnit.body = wrapped
      unitOfFile.put(sourceFile.file, compilationUnit)
      ZIO {
        reporter.attempt {
          run.compileUnits(List(compilationUnit), run.namerPhase)
          exitingTyper(compilationUnit.body)
          // materialize these lazy vals now while the run is still active
          val _1 = cellClassSymbol
          val _2 = cellClassType
          val _3 = cellCompanionSymbol
          val _4 = cellInstSymbol
          val _5 = cellInstType
          val _6 = typedOutputs
        }
      }.lock(compilerThread).absolve
    }.flatten

    // compute which inputs (values from previous cells) are actually referenced in the code
    private def usedInputs = {
      val classSymbol = cellClassSymbol
      val inputNames = inputs.map(_.name).toSet
      compilationUnit.body.collect {
        case Select(This(`assignedTypeName`), name: TermName) if inputNames contains name => name
        case id@Ident(name: TermName) if (id.symbol.ownerChain contains classSymbol) && (inputNames contains name) => name
      }
    }

    // compute which prior cells are actually used – due to referencing a type or method from that cell
    private def usedPriorCells: List[CellCode] = cellClassSymbol.map { cellClassSymbol =>
      val inputCellSymbols = cellClassSymbol.primaryConstructor.paramss.head.toSet
      val inputCellSymbolNames = inputCellSymbols.map(_.name)
      val used = new mutable.HashSet[Name]()

      // first pull out all of the trees that correspond to the input code after typechecking. This includes all
      // synthetic stuff that was generated for the user's code, but not any of our wrapper junk.
      val classBody = compilationUnit.body.collect {
        case ClassDef(_, `assignedTypeName`, _, Template(_, _, stats)) => stats.filter {
          case ValDef(mods, _, _, _) if mods.isParamAccessor => false
          case DefDef(mods, name, _, _, _, _) if mods.isParamAccessor || name.decoded == "<init>" => false
          case tree => !tree.pos.isTransparent
        }
      }.flatten

      // Now go through each of those trees and look for references to previous cells. Ideally this could be just
      // symbol matching, but the symbols don't always seem to match when they ought to, so it matches on the
      // previous cell's accessor name.
      val traverser = new Traverser {
        override def traverse(tree: Tree): Unit = {
          // TODO: this doesn't cover type trees which reference a type imported from a cell
          if (tree.symbol != null) {
            if (inputCellSymbolNames.contains(tree.symbol.name)) {
              used.add(tree.symbol.name)
            }
          }

          // type trees don't get traversed in a useful way by default; traverse its original tree if it has one
          tree match {
            case tree@TypeTree() if tree.original != null => super.traverse(tree.original)
            case _ =>
          }

          super.traverse(tree)
        }
      }

      classBody.foreach(traverser.traverse)

      val results = priorCells.zip(priorCellInputs).filter {
        case (cell, term) => used contains term.name
      }.map(_._1)

      results
    }.getOrElse(Nil)

    def splitImports(): Imports = code match {
      case (pkg @ PackageDef(pkgId, stats)) :: Nil =>
        // TODO: should things you import from inside a package cell be imported elsewhere? Or should it be isolated?
        val selectors = stats.collect {
          case defTree: DefTree => defTree.name
        }.groupBy(_.toString).values.map(_.maxBy(_.isTermName)).zipWithIndex.map {
          case (name, index) => ImportSelector(name.toTermName, index, name.toTermName, index)
        }.toList

        if (selectors.nonEmpty) {
          Imports(Nil, List(Import(copyAndReset(pkgId), selectors)))
        } else {
          Imports(Nil, Nil)
        }
      case _ =>
        val priorCellSymbols = priorCells.flatMap(_.cellClassSymbol)
        val imports = compiledCode.zip(code).collect {
          case (i: Import, orig: Import) => (i, orig)
        }
        val (local, external) = imports.partition {
          case (Import(expr, names), _) =>
            (expr.symbol.ownerChain contains cellClassSymbol.getOrElse(NoSymbol)) || expr.symbol.ownerChain.intersect(priorCellSymbols).nonEmpty
        }
        Imports(local.map(_._2.duplicate), external.map(_._2.duplicate))
    }

    /**
      * Make a new [[CellCode]] that uses a minimal subset of inputs and prior cells.
      * After invoking, this [[CellCode]] will not be compilable – bin it!
      */
    def pruneInputs(): Task[CellCode] = if (inputs.nonEmpty || priorCells.nonEmpty) {
      for {
        typedTree  <- ZIO(reporter.attempt(typed)).lock(compilerThread).absolve
        usedNames  <- ZIO(usedInputs).lock(compilerThread)
        usedDeps   <- ZIO(usedPriorCells)
        usedNameSet = usedNames.map(_.decodedName.toString).toSet
      } yield copy(priorCells = usedDeps, inputs = inputs.filter(usedNameSet contains _.name.decodedName.toString))
    } else ZIO.succeed(copy(priorCells = Nil, inputs = Nil))

    /**
      * Transform the code statements using the given function.
      */
    def transformStats(fn: List[Tree] => List[Tree]): CellCode = code match {
      case PackageDef(_, _) :: Nil => this
      case code                    => copy(code = fn(code))
    }
  }

  case class Imports(
    localImports: List[Import] = Nil,
    externalImports: List[Import] = Nil
  ) {
    def ++(that: Imports): Imports = Imports(localImports ++ that.localImports, externalImports ++ that.externalImports)
  }

}

object ScalaCompiler {

  def access: ZIO[Provider, Nothing, ScalaCompiler]    = ZIO.access[ScalaCompiler.Provider](_.scalaCompiler)
  def settings: ZIO[Provider, Nothing, Settings]       = access.map(_.global.settings)
  def dependencies: ZIO[Provider, Nothing, List[File]] = access.map(_.dependencies)

  private[polynote] def apply(settings: Settings, classLoader: Task[AbstractFileClassLoader], notebookPackage: String = "$notebook"): Task[ScalaCompiler] =
    classLoader.memoize.flatMap {
      classLoader => ZIO {
        val global = new Global(settings, KernelReporter(settings))
        new ScalaCompiler(global, notebookPackage, classLoader, Nil, settings.classpath.value.split(File.pathSeparatorChar).toList.map(new File(_)))
      }
    }

  /**
    * @param dependencyClasspath List of class path entries for direct dependencies. Classes from these entries will be
    *                            prioritized by auto-import/classpath-based completions in participating JVM languages.
    * @param transitiveClasspath List of class path entries for transitive dependencies. These are still loaded in the
    *                            notebook class loader, but they don't get higher priority for autocomplete.
    * @param otherClasspath      List of class path entries which the compiler needs to know about, but which aren't
    *                            going to be loaded by the dependency class loader (and thus will be loaded by the boot
    *                            class loader). This basically means Spark and its ilk.
    * @param modifySettings      A function which will receive the base compiler [[Settings]], and can return modified
    *                            settings which will be used to construct the compiler.
    * @return A [[ScalaCompiler]] instance
    */
  def apply(
    dependencyClasspath: List[File],
    transitiveClasspath: List[File],
    otherClasspath: List[File],
    modifySettings: Settings => Settings
  ): RIO[Config with System, ScalaCompiler] = for {
    settings          <- ZIO(modifySettings(defaultSettings(new Settings(), dependencyClasspath ++ transitiveClasspath ++ otherClasspath)))
    global            <- ZIO(new Global(settings, KernelReporter(settings)))
    notebookPackage    = "$notebook"
    classLoader       <- makeClassLoader(settings, dependencyClasspath ++ transitiveClasspath).memoize
  } yield new ScalaCompiler(global, notebookPackage, classLoader, dependencyClasspath, otherClasspath)

  def makeClassLoader(settings: Settings, dependencyClasspath: List[File]): RIO[Config, AbstractFileClassLoader] = for {
    dependencyClassLoader <- makeDependencyClassLoader(settings, dependencyClasspath)
    compilerOutput        <- ZIO.fromOption(settings.outputDirs.getSingleOutput).mapError(_ => new IllegalArgumentException("Compiler must have a single output directory"))
  } yield new AbstractFileClassLoader(compilerOutput, dependencyClassLoader)

  def makeDependencyClassLoader(settings: Settings, dependencyClasspath: List[File]): RIO[Config, URLClassLoader] = Config.access.flatMap {
    config => ZIO {
      if (config.behavior.dependencyIsolation) {
        new LimitedSharingClassLoader(
          config.behavior.getSharedString,
          dependencyClasspath.map(_.toURI.toURL),
          getClass.getClassLoader)
      } else {
        new URLClassLoader(dependencyClasspath.map(_.toURI.toURL), getClass.getClassLoader)
      }
    }
  }

  /**
    * Like [[apply]], but returns the [[ScalaCompiler]] instance already wrapped in a [[Provider]].
    */
  def provider(
    dependencyClasspath: List[File],
    transitiveClasspath: List[File],
    otherClasspath: List[File],
    modifySettings: Settings => Settings
  ): RIO[Config with System, ScalaCompiler.Provider] = apply(dependencyClasspath, transitiveClasspath, otherClasspath, modifySettings).map(Provider.of)

  def provider(dependencyClasspath: List[File], transitiveClasspath: List[File], otherClasspath: List[File]): RIO[Config with System, ScalaCompiler.Provider] =
    provider(dependencyClasspath, transitiveClasspath, otherClasspath, identity[Settings])

  private def pathAsFile(url: URL): File = url match {
    case url if url.getProtocol == "file" => new File(url.getPath)
    case url => throw new IllegalStateException(s"Required path $url must be a local file, not ${url.getProtocol}")
  }

  val requiredPolynotePaths: List[File] = List(
    pathOf(polynote.runtime.Runtime.getClass),
    pathOf(classOf[jep.python.PyObject])
  ).map(pathAsFile)

  val requiredPaths: List[File] = requiredPolynotePaths ++ List(
    pathOf(classOf[List[_]]),
    pathOf(classOf[scala.reflect.runtime.JavaUniverse]),
    pathOf(classOf[scala.tools.nsc.Global])
  ).distinct.map(pathAsFile)

  def defaultSettings(initial: Settings, classPath: List[File] = Nil): Settings = {
    val cp = classPath ++ requiredPaths

    val settings = initial.copy()
    settings.classpath.append(cp.map(_.getCanonicalPath).mkString(File.pathSeparator))
    settings.Yrangepos.value = true
    try {
      settings.YpartialUnification.value = true
    } catch {
      case err: Throwable =>  // not on Scala 2.11.11+ - that's OK, just won't get partial unification
    }
    settings.exposeEmptyPackage.value = true
    settings.Ymacroexpand.value = settings.MacroExpand.Normal
    settings.YpresentationAnyThread.value = true
    settings.Ydelambdafy.value = "inline"
    settings.outputDirs.setSingleOutput(new VirtualDirectory("(memory)", None))
    settings
  }

  trait Provider {
    val scalaCompiler: ScalaCompiler
  }

  object Provider {
    def of(compiler: ScalaCompiler): Provider = new Provider {
      override val scalaCompiler: ScalaCompiler = compiler
    }
  }

  // Attachment for saving the original position of a tree (before the typer)
  case class OriginalPos(pos: Position)
}