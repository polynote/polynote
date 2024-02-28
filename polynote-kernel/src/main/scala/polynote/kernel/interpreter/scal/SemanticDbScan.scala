package polynote.kernel.interpreter
package scal

import com.github.javaparser.ast.DataKey
import com.github.javaparser.symbolsolver.JavaSymbolSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.{ClassLoaderTypeSolver, CombinedTypeSolver, JarTypeSolver}
import com.github.javaparser.{JavaParser, ParseProblemException, ParserConfiguration, StaticJavaParser, Position => JPosition}
import polynote.kernel.{BaseEnv, ScalaCompiler}
import polynote.kernel.dependency.Artifact
import polynote.kernel.environment.CurrentTask
import polynote.kernel.logging.Logging
import polynote.kernel.task.TaskManager
import polynote.messages.DefinitionLocation
import zio.ZIO.effectTotal
import zio.blocking.{Blocking, effectBlocking}
import zio.clock.Clock
import zio.duration.Duration
import zio.{RIO, Ref, Task, ZIO}

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import scala.meta.interactive.InteractiveSemanticdb
import scala.reflect.internal.util.{BatchSourceFile, Position, SourceFile}
import scala.reflect.io.{AbstractFile, FileZipArchive, ZipArchive}
import scala.tools.nsc.interactive.{Global, NscThief, Response}

class SemanticDbScan (compiler: ScalaCompiler) {

  private val classpath = compiler.global.classPath.asClassPathString
  private val sources = compiler.dependencies.flatMap(_.source)
  val semanticdbGlobal: Global = InteractiveSemanticdb.newCompiler(classpath, List())
  private val compileUnits = new ConcurrentHashMap[AbstractFile, semanticdbGlobal.RichCompilationUnit]
  val javaMapping = new ConcurrentHashMap[String, semanticdbGlobal.RichCompilationUnit]
  private val binariesTypeSolver = new ClassLoaderTypeSolver(compiler.classLoader)
  private val sourcesTypeSolver = new LazyParsingTypeSolver(this, binariesTypeSolver)
  private val symbolSolver = new JavaSymbolSolver(sourcesTypeSolver)
  val javaParser = new JavaParser(
    new ParserConfiguration()
      .setSymbolResolver(symbolSolver)
      .setLanguageLevel(ParserConfiguration.LanguageLevel.RAW)
  )

  semanticdbGlobal.settings.stopAfter.value = List("namer")

  // TODO: this should be a ref
  private var run = new semanticdbGlobal.Run()

  private val importer = semanticdbGlobal.mkImporter(compiler.global)

  def init: RIO[BaseEnv with TaskManager, Unit] = TaskManager.run("semanticdb", "Scanning sources")(scanSources).flatMap {
    sources => TaskManager.run("semanticdb", "Indexing sources")(indexSources(sources))
  }

  def dependencyDefinitions(file: SourceFile, offset: Int): RIO[Blocking with Clock, List[DefinitionLocation]] =
    if (file.file.name.endsWith(".java")) {
      javaDefinitions(file, offset).retryN(3)
    } else {
      treeAt(file.file, offset).flatMap {
        tree => ScalaCompleter.findSymbolOfTree(semanticdbGlobal, Some(this))(tree, offset)
      }
    }

  def lookupPosition(sym: Global#Symbol): semanticdbGlobal.Position = {
    val imported = importer.importSymbol(sym.asInstanceOf[compiler.global.Symbol])
    if (!imported.pos.isDefined && sym.isJava) {
      var s = sym
      while (!s.isTopLevel) {
        s = s.owner
      }
      // find position of the symbol
      val unit = javaMapping.get(s.fullName)
      if (unit != null && unit.body != null) {
        val tree = unit.body

        val defnCandidates = tree.collect {
          case tree: semanticdbGlobal.DefTree if tree.name.decoded == sym.name.decoded => tree
        }
        val withPos = defnCandidates.find(_.pos.isDefined)
        withPos.map(_.pos).getOrElse(semanticdbGlobal.NoPosition)

      } else semanticdbGlobal.NoPosition
    } else imported.pos
  }

  def treeAt(file: AbstractFile, offset: Int): RIO[Blocking with Clock, semanticdbGlobal.Tree] = {
    import semanticdbGlobal._
    val sourceFile = new BatchSourceFile(file)
    val position = scala.reflect.internal.util.Position.offset(sourceFile, offset)
    val unit = compileUnits.get(file)
    if (unit == null)
      return ZIO.succeed(EmptyTree)

    // run.compileLate(unit) // doesn't seem to work
    val check = ZIO.when(!unit.isTypeChecked)(ZIO {
      run.typerPhase.asInstanceOf[semanticdbGlobal.GlobalPhase].apply(unit)
      unit.status = PartiallyChecked
    })

    check *> ZIO(exitingTyper(unit.body)).map {
      tree =>
        val results = tree.collect {
          case tree: Import if tree.pos.properlyIncludes(position)  => tree
          case tree: RefTree if tree.pos.properlyIncludes(position) => tree
          case tree: TypeTree if tree.pos.properlyIncludes(position) => tree
        }.sortBy {
          tree => math.abs(position.start - tree.pos.start)
        }
        results.headOption.getOrElse(EmptyTree)
    }
  }

  private def javaDefinitions(file: SourceFile, offset: Int): RIO[Blocking with Clock, List[DefinitionLocation]] = {
    ZIO(javaParser.parse(new String(file.content)).getResult.get).map {
      cu =>
        val pos = Position.offset(file, offset)
        val line = pos.line
        val col = pos.column
        val result = Option(cu.accept(new JavaTreeFinder(), new JPosition(line, col)))
        result.flatMap(ScalaCompleter.urlOf).toList.map {
          url => DefinitionLocation(url, line, col)
        }
    }
  }

  private def scanSources = Ref.make(0).flatMap {
    completed => ZIO.foreach(sources) {
      file =>
        val scanFile = for {
          arch <- ZIO(new FileZipArchive(file))
          dirsMap <- ZIO(arch.allDirs)
          entries <- ZIO.foreach(dirsMap.valuesCompat)(dir => ZIO(dir.entries.valuesCompat))
        } yield for {
          entry <- entries.flatten
        } yield new BatchSourceFile(entry)

        val updateProgress = for {
          numCompleted <- completed.updateAndGet(_ + 1)
          _            <- CurrentTask.setProgress(numCompleted.toDouble / sources.size)
        } yield ()

        scanFile.orElseSucceed(Nil) <* updateProgress
    }.map(_.flatten)
  }

  private def indexSources(sources: List[SourceFile]) = ZIO {
    // TODO: can use the profiler: scala.tools.nsc.profile.Profiler to track detailed progress here.
    val units = sources.filter {
      sourceFile => sourceFile.path.split('.').last match {
        case "java" | "scala" => true
        case _                => false
      }
    }.map {
      file =>
        val unit = new semanticdbGlobal.RichCompilationUnit(file)
        compileUnits.put(file.file, unit)
        if (file.file.name.endsWith(".java"))
          javaMapping.put(file.path.split("!").last.stripSuffix(".java").replace('/', '.'), unit)
        unit
    }
    // do an initial namer run of all the sources together
    run.compileUnits(units, run.parserPhase)
    units
  }.zipLeft(CurrentTask.setProgress(0.5)).flatMap {
    units =>
      // if there were errors doing that, it could result in some sources getting skipped because of other bad sources.
      // so now loop through them all again, running parser and namer on each one individually.
      Ref.make(0).flatMap {
        completed =>
          ZIO.foreach_(units) {
            unit => ZIO {
              run.parserPhase.asInstanceOf[semanticdbGlobal.GlobalPhase].apply(unit)
              run.namerPhase.asInstanceOf[semanticdbGlobal.GlobalPhase].apply(unit)
            } *> completed.updateAndGet(_ + 1).flatMap {
              numCompleted => CurrentTask.setProgress(0.5 + (numCompleted.toDouble / sources.size * 0.5))
            }
          }
      }.zipLeft(ZIO {
        // namer is enough to figure out which files define which symbols. But to be able to figure out the symbol of
        // what they're clicking on, we'll need to run the typer on that file. So from now on, we'll need to go all the
        // way through the typer.
        semanticdbGlobal.settings.stopAfter.value = List("typer")
        run = new semanticdbGlobal.Run()
      })
  }

}

object SemanticDbScan {

  // Used to attach the source file to the JavaParser CompilationUnit, so we can retrieve it after using SymbolResolver.
  val OriginalSource: DataKey[AbstractFile] = new DataKey[AbstractFile] {}

}

