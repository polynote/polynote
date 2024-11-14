package polynote.kernel
package interpreter
package scal

import java.lang.reflect.{Constructor, InvocationTargetException}
import scala.reflect.internal.util.{NoPosition, Position}
import scala.tools.nsc.interactive.Global
import polynote.messages.{CellID, DefinitionLocation}
import zio.blocking.{Blocking, effectBlockingInterrupt}
import zio.{RIO, Task, ZIO}
import ScalaInterpreter.{addPositionUpdates, captureLastExpression}
import polynote.config.PolynoteConfig
import polynote.kernel.dependency.Artifact
import polynote.kernel.environment.{Config, CurrentNotebook, CurrentRuntime, CurrentTask}
import polynote.kernel.logging.Logging
import polynote.kernel.task.TaskManager
import zio.clock.Clock
import zio.internal.Executor

import java.net.URI
import scala.reflect.io.FileZipArchive

class ScalaInterpreter private[scal] (
  val scalaCompiler: ScalaCompiler,
  indexer: ClassIndexer,
  scan: Option[SemanticDbScan],
  cellThread: Option[Executor] = None
) extends Interpreter {
  import scalaCompiler.{CellCode, global, Imports}
  import global.{Tree, ValDef, TermName, Modifiers, EmptyTree, TypeTree, Import, Name, Type, Quasiquote, typeOf, atPos, NoType}

  ///////////////////////////////////
  // Interpreter interface methods //
  ///////////////////////////////////

  /**
    * Because Polynote's shading tool doesn't know to re-write the classpath for shadow JARs in the Scala metadata,
    * this is a short-term workaround to re-try a failed cell compilation a few times, which eventually causes the
    * issue to resolve. Long-term, the best solution is to change how we do shading, or change the compile method to
    * only retry for certain compilation errors.
    */
  override def run(code: String, state: State): RIO[InterpreterEnv, State] = for {
    (cellCode, inputs, cls) <- compile(code, state)
    resultInstance <- cls.map(cls => runClass(cls, cellCode, inputs, state).map(Some(_))).getOrElse(ZIO.succeed(None))
    resultValues   <- resultInstance.map(resultInstance => getResultValues(state.id, cellCode, resultInstance)).getOrElse(ZIO.succeed(Nil))
  } yield ScalaCellState(state.id, state.prev, resultValues, cellCode, resultInstance)

  private def compile(code: String, state: State) = {
    for {
      collectedState <- injectState(collectState(state))
      valDefs = collectedState.values.mapValues(_._1).values.toList
      cellCode <- scalaCompiler.cellCode(s"Cell${state.id.toString}", code, collectedState.prevCells, valDefs, collectedState.imports)
        .flatMap(_.transformStats(transformCode).pruneInputs())
      inputNames = cellCode.inputs.map(_.name.decodedName.toString)
      inputs = inputNames.map(collectedState.values).map(_._2)
      cls <- scalaCompiler.compileCell(cellCode)
    } yield (cellCode, inputs, cls)
  }.retryN(3)

  override def completionsAt(code: String, pos: Int, state: State): RIO[Blocking, List[Completion]] = for {
    collectedState   <- injectState(collectState(state)).provideLayer(CurrentRuntime.noRuntime)
    valDefs           = collectedState.values.mapValues(_._1).values.toList
    cellCode         <- scalaCompiler.cellCode(s"Cell${state.id.toString}", s"\n\n${code.substring(0, math.min(pos, code.length))}  ", collectedState.prevCells, valDefs, collectedState.imports, strictParse = false)
    completions      <- completer.completions(cellCode, pos + 2)
  } yield completions

  override def parametersAt(code: String, pos: Int, state: State): RIO[Blocking, Option[Signatures]] = for {
    collectedState <- injectState(collectState(state)).provideLayer(CurrentRuntime.noRuntime)
    valDefs         = collectedState.values.mapValues(_._1).values.toList
    cellCode       <- scalaCompiler.cellCode(s"Cell${state.id.toString}", s"\n\n$code  ", collectedState.prevCells, valDefs, collectedState.imports, strictParse = false)
    hints          <- completer.paramHints(cellCode, pos + 2)
  } yield hints

  override def goToDefinition(code: String, pos: Int, state: State): RIO[BaseEnv with CellEnv, List[DefinitionLocation]] = for {
    collectedState <- injectState(collectState(state)).provideLayer(CurrentRuntime.noRuntime)
    valDefs         = collectedState.values.mapValues(_._1).values.toList
    cellCode       <- scalaCompiler.cellCode(s"Cell${state.id.toString}", s"\n\n$code  ", collectedState.prevCells, valDefs, collectedState.imports, strictParse = false)
    locations      <- completer.locateDefinitions(cellCode, pos)
  } yield locations

  override def goToDependencyDefinition(uri: String, pos: Int): RIO[BaseEnv, List[DefinitionLocation]] =
    completer.locateDefinitionsFromDependency(uri, pos)

  override def init(state: State): RIO[InterpreterEnv, State] = ZIO.succeed(state)

  override def shutdown(): Task[Unit] = ZIO.unit

  override def fileExtensions: Set[String] = Set("scala", "java")

  override def getDependencyContent(uri: String): RIO[Blocking, String] = ZIO {
    val parsed = new URI(uri)
    val Seq(jarName, filePath) = parsed.getPath.stripPrefix("/").split('!').toSeq
    val fileDirParts :+ fileName = filePath.split('/').toSeq
    for {
      artifact  <- scalaCompiler.dependencies.find(_.source.exists(_.getName == jarName))
      source    <- artifact.source
      insidePath = fileDirParts.mkString("/") + "/"
      dirEntry  <- new FileZipArchive(source).allDirs.getOpt(insidePath)
      fileEntry <- dirEntry.entries.getOpt(fileName)
    } yield fileEntry
  }.someOrFailException.flatMap {
    entry =>
      ZIO(new String(entry.toCharArray))
  }

  ///////////////////////////////////////
  // Overrideable scala-specific stuff //
  ///////////////////////////////////////

  /**
    * Overrideable method to inject some pre-defined state (values and imports) into the initial cell. The base implementation
    * injects the `kernel` value, making it available to the notebook. Override to inject more imports or values.
    */
  protected def injectState(collectedState: CollectedState): RIO[CurrentRuntime, CollectedState] =
    CurrentRuntime.access.map {
      kernelRuntime =>
        collectedState.copy(values = collectedState.values + (runtimeValDef.name.toString -> (runtimeValDef, kernelRuntime: Any)))
    }

  /**
    * Transforms the Scala statements, to inject additional things like updating the execution status and ensuring
    * the last expression is captured. Override to add additional transformations (don't forget to call super.transformCode!)
    */
  protected def transformCode(code: List[Tree]): List[Tree] = {
    addPositionUpdates(global)(captureLastExpression(global)(code))
  }


  ////////////////////////////////////////////////////
  // Protected structures for subclass implementors //
  ////////////////////////////////////////////////////

  /**
    * Container for information about available values, etc from previous cells or predefined things
    */
  protected case class CollectedState(
    values: Map[String, (ValDef, Any)] = Map.empty,
    imports: Imports = Imports(),
    prevCells: List[CellCode] = Nil)


  //////////////////////////////////
  // Private scala-specific stuff //
  //////////////////////////////////

  private val completer = ScalaCompleter(scalaCompiler, indexer, scan)

  // for testing reliably
  private[scal] def awaitIndexer = indexer.await

  // create the parameter that's used to inject the `kernel` value into cell scope
  private def runtimeValDef = ValDef(Modifiers(global.Flag.IMPLICIT), TermName("kernel"), tq"polynote.runtime.KernelRuntime", EmptyTree)

  /**
    * Goes backward through the state and collects all the output values and imports from previous cells. For Scala cells,
    * it also builds up a list of prior CellCode instances. We start by wrapping a cell's code in a class which has all
    * these available values and all available prior cells as constructor arguments, and then we prune it to only keep
    * the constructor arguments which the code requires.
    */
  private def collectState(state: State): CollectedState = state.prev.collect {
    case ScalaCellState(_, _, values, cellCode, _) =>
      val valuesMap = values.map(v => v.name -> v.value).toMap
      val inputs = cellCode.typedOutputs.map(cleanInput)
        .flatMap {
          v =>
            val (nameString, input) = v.name.decodedName.toString match {
              case "$Out" => "Out" -> v.copy(name = TermName("Out"))
              case name   => name -> v
            }
            valuesMap.get(nameString).map(value => nameString -> (input, value)).toList
        }.toMap
      (inputs, Option(cellCode))
    case state =>
      val inputs = state.values.map {
        v =>
          val name = TermName(v.name)
          name.encodedName.toString -> (ValDef(Modifiers(), name, TypeTree(v.scalaType.asInstanceOf[global.Type]), EmptyTree), v.value)
      }.toMap
      (inputs, None)
  }.foldRight(CollectedState()) {
    case ((nextInputs, cellCode), CollectedState(inputs, imports, priorCells)) =>
      val nextImports = cellCode.map(_.splitImports()).getOrElse(Imports(Nil, Nil))
      CollectedState(inputs ++ nextInputs, imports ++ nextImports, cellCode.map(_ :: priorCells).getOrElse(priorCells))
  }

  /**
    * Ensure an input [[ValDef]] is suitable as a constructor parameter
    */
  private def cleanInput(input: ValDef): ValDef =
    input.copy(mods = input.mods &~ global.Flag.LAZY).duplicate.setPos(NoPosition)

  private def collectPrevInstances(code: CellCode, state: State): List[AnyRef] = {
    val allInstances = state.prev.collect {
      case ScalaCellState(_, _, _, cellCode, Some(inst)) => cellCode.cellClassSymbol -> inst
    }.toMap

    val usedInstances = code.priorCells.map {
      cell => allInstances(cell.cellClassSymbol)
    }

    usedInstances
  }

  private def partitionInputs(code: CellCode, inputValues: List[Any]) = {
    val (implicitInputs, nonImplicitInputs) = code.inputs.zip(inputValues).partition(_._1.mods.isImplicit)
    (nonImplicitInputs.map(_._2.asInstanceOf[AnyRef]), implicitInputs.map(_._2.asInstanceOf[AnyRef]))
  }

  private def createInstance(constructor: Constructor[_], prevInstances: List[AnyRef], inputs: List[Any]): AnyRef = {
    constructor.newInstance(prevInstances ++ inputs.map(_.asInstanceOf[AnyRef]): _*).asInstanceOf[AnyRef]
  }

  /**
    * Run the cell given the loaded compiled class and the input values carried from previous cells (not including
    * prior cell instances themselves). Collects any required prior cell instances (for dependent types and imports)
    * and constructs the cell class (running the code) in an interruptible task while capturing standard output.
    */
  private def runClass(cls: Class[_], code: CellCode, inputValues: List[Any], state: State) = for {
    constructor   <- ZIO(cls.getDeclaredConstructors()(0))
    prevInstances  = collectPrevInstances(code, state)
    (nonImplicitInputs, implicitInputs) = partitionInputs(code, inputValues)
    blockingService <- cellThread match {
      case Some(exec) => ZIO.succeed(new Blocking.Service { def blockingExecutor = exec })
      case None       => ZIO.service[Blocking.Service]
    }
    run            = blockingService.effectBlockingInterrupt(createInstance(constructor, prevInstances, nonImplicitInputs ++ implicitInputs))
    instance      <- run.catchSome {
      case err: InvocationTargetException if !(err.getCause eq err) && err.getCause != null => ZIO.fail(err.getCause)
    }
  } yield instance

  private def getResultValues(id: CellID, code: CellCode, result: AnyRef) = {
    val cls = result.getClass
    val typedOuts = code.typedOutputs
    scalaCompiler.formatTypes(typedOuts.map(_.tpt.tpe)).flatMap {
      typeNames => effectBlockingInterrupt {
        typedOuts.zip(typeNames).collect {
          case (v, typeName) if !(v.tpt.tpe.typeSymbol.name.decoded == "Unit") && !(v.tpt.tpe.typeSymbol.name.decoded == "BoxedUnit") =>
            val value = cls.getDeclaredMethod(v.name.encodedName.toString).invoke(result)
            val name = v.name.decoded match {
              case "$Out" => "Out"
              case name => name
            }
            ResultValue(name, typeName, Nil, id, value, v.tpt.tpe, Some((v.pos.start, v.pos.end)))
        }
      }
    }
  }

  ///////////////////////////////////////////

  /**
    * A [[State]] implementation for Scala cells. It tracks the CellCode and the instance of the cell class, which
    * we'll need to pass into future cells if they use types, classes, etc from this cell.
    */
  case class ScalaCellState(id: CellID, prev: State, values: List[ResultValue], cellCode: CellCode, instance: Option[AnyRef]) extends State {
    override def withPrev(prev: State): ScalaCellState = copy(prev = prev)
    override def updateValues(fn: ResultValue => ResultValue): State = copy(values = values.map(fn))
    override def updateValuesM[R](fn: ResultValue => RIO[R, ResultValue]): RIO[R, State] =
      ZIO.collectAll(values.map(fn)).map(values => copy(values = values))
  }

}

object ScalaInterpreter {

  def maybeScan(compiler: ScalaCompiler): RIO[BaseEnv with Config with TaskManager, Option[SemanticDbScan]] =
    Config.access.flatMap {
      case config if config.downloadSources =>
        for {
          scan <- ZIO(new SemanticDbScan(compiler))
          _    <- scan.init.forkDaemon
        } yield Some(scan)
      case _ => ZIO.none
    }

  def apply(): RIO[BaseEnv with Config with TaskManager with ScalaCompiler.Provider, ScalaInterpreter] = for {
    compiler <- ScalaCompiler.access
    index    <- ClassIndexer.default
    scan     <- maybeScan(compiler)
  } yield new ScalaInterpreter(compiler, index, scan)

  // capture the last statement in a value Out, if it's a free expression
  def captureLastExpression(global: Global)(trees: List[global.Tree]): List[global.Tree] = {
    import global._
    trees.reverse match {
      case Nil => Nil
      case l :: r => l match {
        case v: ValDef => (v :: r).reverse
        case expr if expr.isTerm => (atPos(expr.pos)(ValDef(Modifiers(), TermName("$Out"), TypeTree(NoType), expr)) :: r).reverse
        case v => (v :: r).reverse
      }
    }
  }

  // Notify the `kernel` of progress and execution status during the cell execution
  def addPositionUpdates(global: Global)(trees: List[global.Tree]): List[global.Tree] = {
    import global._
    val numTrees = trees.size
    if (numTrees == 0) return Nil
    val lastTree = trees.last
    trees.zipWithIndex.flatMap {
      case (tree, index) =>
        val treeProgress = Literal(Constant(index.toDouble / numTrees))
        val lineStr = s"Line ${tree.pos.line}"
        val sPos = tree.pos.makeTransparent
        // code to notify kernel of progress in the cell
        def setProgress(detail: String) =
          atPos(sPos)(q"""kernel.setProgress($treeProgress, ${Literal(Constant(detail))})""")
        def setPos(mark: Tree) =
          if(mark.pos.isRange)
            Some(atPos(sPos)(q"""kernel.setExecutionStatus(${Literal(Constant(mark.pos.start))}, ${Literal(Constant(mark.pos.end))})"""))
          else None

        def wrapWithProgress(name: String, tree: Tree): List[Tree] =
          setPos(tree).toList ++ List(setProgress(name), tree)

        tree match {
          case tree: global.ValDef => wrapWithProgress(tree.name.decodedName.toString, tree)
          case tree: global.MemberDef => List(tree)
          case tree: global.Import => List(tree)
          case tree => wrapWithProgress(lineStr, tree)
        }
    } :+ atPos(lastTree.pos.makeTransparent)(q"kernel.clearExecutionStatus()")
  }

  trait Factory extends Interpreter.Factory {
    val languageName = "Scala"
    def apply(): RIO[BaseEnv with GlobalEnv with ScalaCompiler.Provider with CurrentNotebook with CurrentTask with TaskManager, ScalaInterpreter]
  }

  /**
    * The Scala interpreter factory is a little bit special, in that it doesn't do any dependency fetching. This is
    * because the JVM dependencies must already be fetched when the kernel is booted.
    */
  object Factory extends Factory {
    override def apply(): RIO[BaseEnv with GlobalEnv with ScalaCompiler.Provider with CurrentNotebook with CurrentTask with TaskManager, ScalaInterpreter] = ScalaInterpreter()
  }
}