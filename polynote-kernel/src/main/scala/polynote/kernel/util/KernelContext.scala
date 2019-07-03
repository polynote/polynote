package polynote.kernel.util

import java.io.File
import java.net.URL
import java.util.concurrent.{ExecutorService, Executors, ThreadFactory}

import cats.effect.IO
import cats.syntax.either._
import polynote.config.{PolyLogger, PolynoteConfig}
import polynote.messages.truncateTinyString
import polynote.runtime.{ReprsOf, StringRepr, ValueRepr}

import scala.reflect.internal.util.{AbstractFileClassLoader, BatchSourceFile, RangePosition}
import scala.reflect.internal.util.ScalaClassLoader.URLClassLoader
import scala.reflect.io.{AbstractFile, VirtualDirectory}
import scala.reflect.runtime.{universe => ru}
import scala.tools.nsc.Settings
import scala.tools.nsc.interactive.Global
import scala.tools.reflect.ToolBox
import polynote.kernel.KernelStatusUpdate
import polynote.kernel.lang.scal.CellSourceFile

import scala.collection.mutable
import scala.concurrent.ExecutionContext

final case class KernelContext(global: Global, classPath: List[File], classLoader: AbstractFileClassLoader) {
  import global.{Type, Symbol}

  private val logger = new PolyLogger

  private val reporter = global.reporter.asInstanceOf[KernelReporter]

  val runtimeMirror: ru.Mirror = ru.runtimeMirror(classLoader)

  val importToRuntime: Importer[ru.type, global.type] = ru.internal.createImporter(global)

  val importFromRuntime: Importer[global.type, ru.type] = global.internal.createImporter(ru)

  val runtimeTools: ToolBox[ru.type] = runtimeMirror.mkToolBox()

  val executor: ExecutorService = Executors.newCachedThreadPool(new ThreadFactory {
    def newThread(r: Runnable): Thread = {
      val thread = new Thread(r)
      thread.setContextClassLoader(classLoader)
      thread
    }
  })

  val executionContext: ExecutionContext = ExecutionContext.fromExecutorService(executor)

  object implicits {
    implicit val executionContext: ExecutionContext = KernelContext.this.executionContext
  }

  def inferType(value: Any): global.Type = {
    val instMirror = runtimeMirror.reflect(value)
    val importedSym = importFromRuntime.importSymbol(instMirror.symbol)

    importedSym.toType match {
      case typ if typ.takesTypeArgs =>
        global.appliedType(typ, List.fill(typ.typeParams.size)(global.typeOf[Any]))
      case typ => typ.widen
    }
  }

  def formatType(typ: global.Type): String = global.ask { () =>
    typ match {
      case mt @ global.MethodType(params: List[Symbol], result: global.Type) =>
        val paramStr = params.map {
          sym => s"${sym.nameString}: ${formatType(sym.typeSignatureIn(mt))}"
        }.mkString(", ")
        val resultType = formatType(result)
        s"($paramStr) => $resultType"
      case global.NoType => "<Unknown>"
      case _ =>
        val typName = typ.typeSymbolDirect.name
        val typNameStr = typ.typeSymbolDirect.nameString
        typ.typeArgs.map(formatType) match {
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
  }

  private val reprsOfCache = new mutable.HashMap[global.Type, (ReprsOf[Any], global.Tree)]()

  def reprsOf(value: Any, typ: global.Type): List[ValueRepr] = if (value == null) List(StringRepr("null")) else {
    val otherReprs = try {
      reprsOfCache.getOrElseUpdate(typ,
        {
         // val run = new global.Run()
          val appliedType = global.appliedType(global.typeOf[ReprsOf[_]].typeConstructor, typ)

          val fromGlobal = inferImplicit(appliedType).flatMap {
            tree => Either.catchNonFatal {
              val imported = importToRuntime.importTree(tree)
              runtimeTools.eval(runtimeTools.untypecheck(imported)).asInstanceOf[ReprsOf[Any]] -> tree
            }
          }

          fromGlobal.getOrElse {
            ReprsOf.empty -> global.EmptyTree
          }
        }
      )._1.apply(value)
    } catch {
      case err: Throwable =>
        val e = err
        logger.debug(err)(s"Error resolving reprs of $typ")
        Array.empty[ValueRepr]
    }

    val stringRepr = if (otherReprs.exists(_.isInstanceOf[StringRepr]))
      None
    else
      Some(StringRepr(truncateTinyString(Option(value).map(_.toString).getOrElse(""))))



    stringRepr.toList ++ otherReprs.toList
  }

  def inferImplicit(typ: global.Type): Either[Throwable, global.Tree] = {
    import global.{reporter => _, _}
    val objectName = freshTermName("anonImplicit")(global.globalFreshNameCreator)
    val termName = freshTermName("value")(global.globalFreshNameCreator)
    val sourceFile = CellSourceFile(objectName.toString)
    val unit = new RichCompilationUnit(sourceFile)
    val cachedImports = reprsOfCache.toList.filter(_._2._2 != EmptyTree).collect {
      case (cachedTyp, (_, cachedTree@Select(qual, cachedName))) =>
        val copyOfCached = Select(qual, cachedName)
        q"private implicit def ${cachedName.toTermName}: _root_.polynote.runtime.ReprsOf[$cachedTyp] = $copyOfCached"
    }

    unit.body =
      atPos(new RangePosition(sourceFile, 0, 0, 0)) {
        q"""
          package anonImplicits {
            object $objectName extends scala.Serializable {
              ..$cachedImports
              lazy val $termName: $typ = implicitly[$typ]
            }
          }
        """
      }

    val result = global.ask {
      () =>
        reporter.attempt {
          val run = new Run()
          run.compileUnits(List(unit), run.namerPhase)
          global.exitingTyper(unit.body)
          new Run()
        }
    }.map {
      tree => q"anonImplicits.$objectName.$termName"
    }

    result
  }

  @volatile private var currentTaskThread: Thread = _
  private object taskThreadMonitor
  private object taskMonitor

  def runInterruptible[T](thunk: => T): T = taskMonitor.synchronized {
    val prev = taskThreadMonitor.synchronized {
      val prev = currentTaskThread
      currentTaskThread = Thread.currentThread()
      prev
    }

    val result = try thunk finally {
      Thread.currentThread().setContextClassLoader(classLoader)
      taskThreadMonitor.synchronized {
        currentTaskThread = prev
      }
    }

    result
  }

  def runInterruptibleIO[T](thunk: => T): IO[T] = IO.delay(runInterruptible(thunk))

  def interrupt(): Unit = {
    taskThreadMonitor.synchronized(Option(currentTaskThread)) match {
      case None => ()
      case Some(thread) => thread.interrupt()
    }
  }

}

object KernelContext {
  def defaultBaseSettings: Settings = new Settings()
  def defaultOutputDir: AbstractFile = new VirtualDirectory("(memory)", None)
  def defaultParentClassLoader: ClassLoader = getClass.getClassLoader

  def genNotebookClassLoader(
    dependencies: Map[String, List[(String, File)]],
    extraClassPath: List[File],
    outputDir: AbstractFile,
    parentClassLoader: ClassLoader
  ): AbstractFileClassLoader = {

    def dependencyClassPath: Seq[URL] = dependencies.toSeq.flatMap(_._2).collect {
      case (_, file) if (file.getName endsWith ".jar") && file.exists() => file.toURI.toURL
    }

    /**
      * The class loader which loads the dependencies
      */
    val dependencyClassLoader: URLClassLoader = new LimitedSharingClassLoader(
      "^(scala|javax?|jdk|sun|com.sun|com.oracle|polynote|org.w3c|org.xml|org.omg|org.ietf|org.jcp|org.apache.spark|org.apache.hadoop|org.codehaus|org.slf4j|org.log4j)\\.",
      dependencyClassPath,
      parentClassLoader)

    /**
      * The class loader which loads the JVM-based notebook cell classes
      */
    new AbstractFileClassLoader(outputDir, dependencyClassLoader)
  }

  def default(
    dependencies: Map[String, List[(String, File)]],
    statusUpdates: Publish[IO, KernelStatusUpdate],
    extraClassPath: List[File]
  ): KernelContext = apply(dependencies, statusUpdates, defaultBaseSettings, extraClassPath, defaultOutputDir, defaultParentClassLoader)

  def apply(
    dependencies: Map[String, List[(String, File)]],
    statusUpdates: Publish[IO, KernelStatusUpdate],
    baseSettings: Settings,
    extraClassPath: List[File],
    outputDir: AbstractFile,
    parentClassLoader: ClassLoader
  ): KernelContext = {

    val settings = baseSettings.copy()
    val jars = dependencies.toList.flatMap(_._2).collect {
      case (_, file) if file.getName endsWith ".jar" => file
    }
    val requiredPaths = List(
      pathOf(classOf[List[_]]),
      pathOf(polynote.runtime.Runtime.getClass),
      pathOf(classOf[scala.reflect.runtime.JavaUniverse]),
      pathOf(classOf[scala.tools.nsc.Global]),
      pathOf(classOf[jep.python.PyObject])
    ).distinct.map {
      case url if url.getProtocol == "file" => new File(url.getPath)
      case url => throw new IllegalStateException(s"Required path $url must be a local file, not ${url.getProtocol}")
    }

    val classPath = jars ++ requiredPaths ++ extraClassPath
    settings.classpath.append(classPath.map(_.getCanonicalPath).mkString(File.pathSeparator))

    settings.Yrangepos.value = true
    try {
      settings.YpartialUnification.value = true
    } catch {
      case err: Throwable =>  // not on Scala 2.11.11+ - that's OK, just won't get partial unification
    }
    settings.exposeEmptyPackage.value = true
    settings.Ymacroexpand.value = settings.MacroExpand.Normal
    settings.outputDirs.setSingleOutput(outputDir)
    settings.YpresentationAnyThread.value = true

    val reporter = KernelReporter(settings)

    val global = new Global(settings, reporter)

    // Not sure why this has to be done, but otherwise the compiler eats it
    global.ask {
      () => new global.Run().compileSources(List(new BatchSourceFile("<init>", "class $repl_$init { }")))
    }

    val notebookClassLoader = genNotebookClassLoader(dependencies, extraClassPath, outputDir, parentClassLoader)

    KernelContext(global, classPath, notebookClassLoader)
  }
}

case object NoReprs extends Throwable