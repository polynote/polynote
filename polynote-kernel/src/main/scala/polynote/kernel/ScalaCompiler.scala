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
  ): RIO[Blocking, Class[_]] =
    for {
      compiled  <- cellCode.compile()
      className  = s"${packageName.encodedName.toString}.${cellCode.assignedTypeName.encodedName.toString}"
      cl        <- classLoader
      cls       <- zio.blocking.effectBlocking(Class.forName(className, false, cl).asInstanceOf[Class[AnyRef]])
    } yield cls

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
    parsed      <- if (strictParse) ZIO(reporter.attempt(newUnitParser(compileUnit).parseStats())).absolve else ZIO(newUnitParser(compileUnit).parseStats())
  } yield {
    val definedTerms = parsed.collect {
      case tree: DefTree if tree.name.isTermName => tree.name.decoded
    }.toSet

    val allowedInputs = inputs.filterNot(definedTerms contains _.name.decoded)

    CellCode(
      name, parsed, priorCells, allowedInputs, inheritedImports, compileUnit, sourceFile
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
      cls => (construct >>= getFields(names)).provide(cls)
    }.catchAll {
      err =>
        // if that doesn't compile (i.e. some implicits are missing) we'll try to compile each individually
        trees.zip(names).map {
          case (tree, name) =>
            compileCell(CellCode(cellName, List(tree))).flatMap {
              cls => (construct >>= getField(name)).provide(cls)
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

  private def template(stats: Tree*): Template = template(stats.toList)

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
    lazy val compiledCode: List[Tree] = code.map {
      stat => stat.duplicate.setPos(stat.pos)
    }


    // The name of the class (and its companion object, in case one is needed)
    lazy val assignedTypeName: TypeName = freshTypeName(s"$name$$")(global.globalFreshNameCreator)
    lazy val assignedTermName: TermName = assignedTypeName.toTermName

    private lazy val priorCellNames = priorCells.map(_.assignedTypeName)

    // create constructor parameters to hold instances of prior cells; these are needed to access path-dependent types
    // for any classes, traits, type aliases, etc defined by previous cells
    lazy val priorCellInputs: List[ValDef] = priorCells.map {
      cell => ValDef(Modifiers(), TermName(s"_input${cell.assignedTypeName.decodedName.toString}"), TypeTree(cell.cellInstType).setType(cell.cellInstType), EmptyTree)
    }

    lazy val typedInputs: List[ValDef] = inputs

    // Separate the implicit inputs, since they must be in their own parameter list
    lazy val (implicitInputs: List[ValDef], nonImplicitInputs: List[ValDef]) = typedInputs.partition(_.mods.isImplicit)

    // a position to encompass the whole synthetic tree
    private lazy val wrappedPos = Position.transparent(sourceFile, 0, 0, sourceFile.length + 1)

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
      case valDef: ValDef if valDef.mods.isPublic => valDef.duplicate
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
      val dependentTypeMapping = priorCellInputs.zip(priorCells).map {
        case (input, cell) => input.name.toString -> cell.cellInstType
      }.toMap

      def transformType(typ: Type): Type = typ match {
        case TypeRef(pre, sym, Nil) =>
          typeRef(transformType(pre), sym, Nil)
        case TypeRef(pre, sym, args) =>
          typeRef(transformType(pre), sym, args.mapConserve(transformType))
        case SingleType(pre, sym) if pre.typeSymbol == mySym && (dependentTypeMapping contains sym.name.toString) =>
          global.internal.singleType(dependentTypeMapping(sym.name.toString), sym)
        case SingleType(pre, sym) =>
          global.internal.singleType(transformType(pre), sym)
        case `myType` =>
          cellInstType
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

    lazy val typedMethods: List[MethodSymbol] = compiledCode.collect {
      case method: DefDef if method.mods.isPublic && method.symbol != NoSymbol && method.symbol != null && method.symbol.isMethod =>
        method.symbol.asMethod
    }

    // what things does this code import?
    lazy val imports: List[Import] = code.collect {
      case i: Import => i.duplicate
    }

    // what types (classes, traits, objects, type aliases) and methods does this code define?
    lazy val definedTypesAndMethods: List[Name] = code.collect {
      case ModuleDef(mods, name, _) if mods.isPublic => name
      case ClassDef(mods, name, _, _) if mods.isPublic => name.toTermName
      case TypeDef(mods, name, _, _) if mods.isPublic => name.toTermName
      case DefDef(mods, name, _, _, _, _) if mods.isPublic => name
    }.distinct

    // The code all wrapped up in a class definition, with constructor arguments for the given prior cells and inputs
    private lazy val wrappedClass: ClassDef = {
      val priorCellParamList = copyAndReset(priorCellInputs)
      val nonImplicitParamList = copyAndReset(nonImplicitInputs)
      val implicitParamList = copyAndReset(implicitInputs)
      q"""
        final class $assignedTypeName(..$priorCellParamList)(..$nonImplicitParamList)(..$implicitParamList) extends scala.Serializable {
          ..${priorCellImports}
          ..${copyAndReset(inheritedImports.externalImports)}
          ..${copyAndReset(inheritedImports.localImports)}
          ..${compiledCode}
        }
      """
    }

    private lazy val companion: ModuleDef = {
      q"""
         object $assignedTermName extends {
           final val instance: $assignedTypeName = null
           type Inst = instance.type
         }
       """
    }

    // Wrap the code in a class within the given package. Constructing the class runs the code.
    // The constructor parameters are
    private lazy val wrapped: PackageDef = atPos(wrappedPos) {
      q"""
        package $packageName {
          $wrappedClass
          $companion
        }
      """
    }

    // the type representing this cell's class. It may be null or NoType if invoked before compile is done!
    lazy val cellClassType: Type = exitingTyper(wrappedClass.symbol.info)
    lazy val cellClassSymbol: ClassSymbol = exitingTyper(wrappedClass.symbol.asClass)
    lazy val cellCompanionSymbol: ModuleSymbol = exitingTyper(companion.symbol.asModule)
    lazy val cellInstSymbol: Symbol = exitingTyper(cellCompanionSymbol.info.member(TermName("instance")).accessedOrSelf)
    lazy val cellInstType: Type = exitingTyper(cellCompanionSymbol.info.member(TypeName("Inst")).info.dealias)

    // Note – you mustn't typecheck and then compile the same instance; those trees are done for. Instead, make a copy
    // of this CellCode and typecheck that if you need info about the typed trees without compiling all the way
    private[kernel] lazy val typed = {
      val run = new Run()
      compilationUnit.body = wrapped
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
    private def usedPriorCells: List[CellCode] = {
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
    }

    def splitImports(): Imports = {
      val priorCellSymbols = priorCells.map(_.cellClassSymbol)
      val imports = compiledCode.zip(code).collect {
        case (i: Import, orig: Import) => (i, orig)
      }
      val (local, external) = imports.partition {
        case (Import(expr, names), _) =>
          (expr.symbol.ownerChain contains cellClassSymbol) || expr.symbol.ownerChain.intersect(priorCellSymbols).nonEmpty
      }

      Imports(local.map(_._2.duplicate), external.map(_._2.duplicate))
    }

    /**
      * Make a new [[CellCode]] that uses a minimal subset of inputs and prior cells.
      * After invoking, this [[CellCode]] will not be compilable – bin it!
      */
    def pruneInputs(): Task[CellCode] = for {
      typedTree  <- ZIO(reporter.attempt(typed)).lock(compilerThread).absolve
      usedNames  <- ZIO(usedInputs).lock(compilerThread)
      usedDeps   <- ZIO(usedPriorCells)
      usedNameSet = usedNames.map(_.decodedName.toString).toSet
    } yield copy(priorCells = usedDeps, inputs = inputs.filter(usedNameSet contains _.name.decodedName.toString))

    /**
      * Transform the code statements using the given function.
      */
    def transformCode(fn: List[Tree] => List[Tree]): CellCode = copy(code = fn(code))
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

  def apply(settings: Settings, classLoader: Task[AbstractFileClassLoader], notebookPackage: String = "$notebook"): Task[ScalaCompiler] =
    classLoader.memoize.flatMap {
      classLoader => ZIO {
        val global = new Global(settings, KernelReporter(settings))
        new ScalaCompiler(global, notebookPackage, classLoader, Nil, settings.classpath.value.split(File.pathSeparatorChar).toList.map(new File(_)))
      }
    }

  def apply(
    dependencyClasspath: List[File],
    otherClasspath: List[File],
    modifySettings: Settings => Settings
  ): RIO[Config with System, ScalaCompiler] = for {
    settings          <- ZIO(modifySettings(defaultSettings(new Settings(), dependencyClasspath ++ otherClasspath)))
    global            <- ZIO(new Global(settings, KernelReporter(settings)))
    notebookPackage    = "$notebook"
    classLoader       <- makeClassLoader(settings).memoize
  } yield new ScalaCompiler(global, notebookPackage, classLoader, dependencyClasspath, otherClasspath)

  def makeClassLoader(settings: Settings): RIO[Config, AbstractFileClassLoader] = for {
    dependencyClassLoader <- makeDependencyClassLoader(settings)
    compilerOutput        <- ZIO.fromOption(settings.outputDirs.getSingleOutput).mapError(_ => new IllegalArgumentException("Compiler must have a single output directory"))
  } yield new AbstractFileClassLoader(compilerOutput, dependencyClassLoader)

  def makeDependencyClassLoader(settings: Settings): RIO[Config, URLClassLoader] = Config.access.flatMap {
    config => ZIO {
      val dependencyClassPath = settings.classpath.value.split(File.pathSeparator).toSeq.map(new File(_).toURI.toURL)

      if (config.behavior.dependencyIsolation) {
        new LimitedSharingClassLoader(
          "^(scala|javax?|jdk|sun|com.sun|com.oracle|polynote|org.w3c|org.xml|org.omg|org.ietf|org.jcp|org.apache.spark|org.spark_project|org.glassfish.jersey|org.jvnet.hk2|org.apache.hadoop|org.codehaus|org.slf4j|org.log4j|org.apache.log4j)\\.",
          dependencyClassPath,
          getClass.getClassLoader)
      } else {
        new URLClassLoader(dependencyClassPath, getClass.getClassLoader)
      }
    }
  }

  def provider(
    dependencyClasspath: List[File],
    otherClasspath: List[File],
    modifySettings: Settings => Settings
  ): RIO[Config with System, ScalaCompiler.Provider] = apply(dependencyClasspath, otherClasspath, modifySettings).map(Provider.of)

  def provider(dependencyClasspath: List[File], otherClasspath: List[File]): RIO[Config with System, ScalaCompiler.Provider] =
    provider(dependencyClasspath, otherClasspath, identity[Settings])

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

}