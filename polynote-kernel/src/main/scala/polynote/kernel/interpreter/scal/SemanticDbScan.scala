package polynote.kernel.interpreter
package scal

import polynote.kernel.{BaseEnv, ScalaCompiler}
import polynote.kernel.dependency.Artifact
import polynote.kernel.environment.CurrentTask
import polynote.kernel.logging.Logging
import polynote.kernel.task.TaskManager
import zio.ZIO.effectTotal
import zio.blocking.{Blocking, effectBlocking}
import zio.clock.Clock
import zio.duration.Duration
import zio.{RIO, Ref, Task, ZIO}

import java.util.concurrent.ConcurrentHashMap
import scala.meta.interactive.InteractiveSemanticdb
import scala.reflect.internal.util.{BatchSourceFile, SourceFile}
import scala.reflect.io.{AbstractFile, FileZipArchive}
import scala.tools.nsc.{Global, interactive}
import scala.tools.nsc.interactive.{NscThief, Response}

class SemanticDbScan (compiler: ScalaCompiler) {

  private val classpath = compiler.global.classPath.asClassPathString//.dependencies.map(_.file.getAbsolutePath).mkString(File.pathSeparator)
  private val sources = compiler.dependencies.flatMap(_.source)
  val semanticdbGlobal: interactive.Global = InteractiveSemanticdb.newCompiler(classpath, List())
  private val compileUnits = new ConcurrentHashMap[AbstractFile, semanticdbGlobal.RichCompilationUnit]
  private val javaMapping = new ConcurrentHashMap[String, semanticdbGlobal.RichCompilationUnit]
  semanticdbGlobal.settings.stopAfter.value = List("namer")

  // TODO: this should be a ref
  private var run = new semanticdbGlobal.Run()

  private val importer = semanticdbGlobal.mkImporter(compiler.global)

  def init: RIO[BaseEnv with TaskManager, Unit] = TaskManager.run("semanticdb", "Scanning sources")(scanSources).flatMap {
    sources => TaskManager.run("semanticdb", "Indexing sources")(indexSources(sources))
  }

  def lookupPosition(sym: Global#Symbol): semanticdbGlobal.Position = {
    val imported = importer.importSymbol(sym.asInstanceOf[compiler.global.Symbol])
    if (!imported.pos.isDefined && sym.isJava) {
      var s = sym
      while (!s.isTopLevel) {
        s = s.owner
      }
      // find position of the symbol
      val tree = javaMapping.get(s.fullName).body

      if (tree == null) {
        semanticdbGlobal.NoPosition
      } else {
        val defnCandidates = tree.collect {
          case tree: semanticdbGlobal.DefTree if tree.name.decoded == sym.name.decoded => tree
        }
        val withPos = defnCandidates.find(_.pos.isDefined)
        withPos.map(_.pos).getOrElse(semanticdbGlobal.NoPosition)
      }
    } else imported.pos
  }

  def treeAt(file: AbstractFile, offset: Int): RIO[Blocking with Clock, semanticdbGlobal.Tree] = {
    import semanticdbGlobal._
    val sourceFile = new BatchSourceFile(file)
    val position = scala.reflect.internal.util.Position.offset(sourceFile, offset)
    val unit = compileUnits.get(file)
    if (unit == null)
      return ZIO.succeed(EmptyTree)

//    run.compileLate(unit) // doesn't seem to work
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


  def index(sourceFile: SourceFile): Task[semanticdbGlobal.Tree] = for {
    response     <- ZIO(new Response[semanticdbGlobal.Tree])
    _            <- ZIO(semanticdbGlobal.askLoadedTyped(sourceFile, false, response))
    _            <- {
      ZIO.sleep(Duration.fromMillis(100)).provideLayer(Clock.live) *> effectTotal(response.isComplete)
    }.repeatUntilEquals(true)
    result       <- ZIO.fromEither(response.get.swap)
  } yield result

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
        if (file.isJava)
          javaMapping.put(file.path.split("!").last.stripSuffix(".java").replace('/', '.'), unit)
        unit
    }
    // do an initial namer run of all the sources together
    run.compileUnits(units, run.parserPhase)
    units
  }.flatMap {
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
              numCompleted => CurrentTask.setProgress(numCompleted.toDouble / sources.size)
            }
          }
      }.zipLeft(ZIO {
        semanticdbGlobal.settings.stopAfter.value = List("typer")
        run = new semanticdbGlobal.Run()
      })
  }

}

object SemanticDbScan {

}

