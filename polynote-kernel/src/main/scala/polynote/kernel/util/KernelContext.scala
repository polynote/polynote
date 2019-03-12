package polynote.kernel.util

import java.io.File
import java.net.URL

import polynote.messages.truncateTinyString
import polynote.runtime.{ReprsOf, StringRepr, ValueRepr}

import scala.reflect.internal.util.{AbstractFileClassLoader, BatchSourceFile}
import scala.reflect.internal.util.ScalaClassLoader.URLClassLoader
import scala.reflect.io.{AbstractFile, VirtualDirectory}
import scala.reflect.runtime.{universe => ru}
import scala.tools.nsc.Settings
import scala.tools.nsc.interactive.Global
import scala.tools.reflect.ToolBox
import org.log4s.{Logger, getLogger}

import scala.collection.mutable

final case class KernelContext(global: Global, classPath: List[File], classLoader: AbstractFileClassLoader) {
  import global.{Type, Symbol}

  private val logger = getLogger

  val runtimeMirror: ru.Mirror = ru.runtimeMirror(classLoader)

  val importToRuntime: Importer[ru.type, global.type] = ru.internal.createImporter(global)

  val importFromRuntime: Importer[global.type, ru.type] = global.internal.createImporter(ru)

  val runtimeTools: ToolBox[ru.type] = runtimeMirror.mkToolBox()

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

  private val reprsOfCache = new mutable.HashMap[global.Type, ReprsOf[Any]]()

  def reprsOf(value: Any, typ: global.Type): List[ValueRepr] = {
    val otherReprs = try {
      reprsOfCache.getOrElseUpdate(typ,
        runtimeTools.inferImplicitValue(ru.appliedType(ru.typeOf[ReprsOf[_]].typeConstructor, importToRuntime.importType(typ))) match {
          case ru.EmptyTree => ReprsOf.empty
          case tree =>
            val untyped = runtimeTools.untypecheck(tree)
            runtimeTools.eval(untyped).asInstanceOf[ReprsOf[Any]]
        }
      ).apply(value)
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
      taskThreadMonitor.synchronized {
        currentTaskThread = prev
      }
    }

    result
  }

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
    val dependencyClassLoader: URLClassLoader =
    new LimitedSharingClassLoader(
      "^(scala|javax?|jdk|sun|com.sun|com.oracle|polynote|org.w3c|org.xml|org.omg|org.ietf|org.jcp|org.apache.spark|org.apache.hadoop|org.codehaus|org.slf4j)\\.",
      dependencyClassPath,
      parentClassLoader)

    /**
      * The class loader which loads the JVM-based notebook cell classes
      */
    new AbstractFileClassLoader(outputDir, dependencyClassLoader)
  }

  def apply(
    dependencies: Map[String, List[(String, File)]],
    extraClassPath: List[File]
  ): KernelContext = apply(dependencies, defaultBaseSettings, extraClassPath, defaultOutputDir, defaultParentClassLoader)

  def apply(
    dependencies: Map[String, List[(String, File)]],
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