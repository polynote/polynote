package polynote.kernel.util

import java.io.File
import java.net.URL

import polynote.kernel.{StringRepr, ValueRepr}
import polynote.messages.truncateTinyString

import scala.reflect.internal.util.{AbstractFileClassLoader, BatchSourceFile}
import scala.reflect.internal.util.ScalaClassLoader.URLClassLoader
import scala.reflect.io.{AbstractFile, VirtualDirectory}
import scala.reflect.runtime
import scala.reflect.runtime.universe
import scala.tools.nsc.Settings
import scala.tools.nsc.interactive.Global
import scala.tools.reflect.ToolBox

final case class KernelContext(global: Global, classPath: List[File], classLoader: AbstractFileClassLoader) {
  import global.{Type, Symbol}

  val runtimeMirror: universe.Mirror = scala.reflect.runtime.universe.runtimeMirror(classLoader)

  val importToRuntime: Importer[runtime.universe.type, global.type] = runtime.universe.internal.createImporter(global)

  val importFromRuntime: Importer[global.type, runtime.universe.type] = global.internal.createImporter(scala.reflect.runtime.universe)

  val runtimeTools: ToolBox[universe.type] = runtimeMirror.mkToolBox()

  def inferType(value: Any): Type = {
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

  def reprsOf(value: Any, typ: global.Type): List[ValueRepr] = {
    val stringRepr = StringRepr(truncateTinyString(Option(value).map(_.toString).getOrElse("")))
    // TODO: look up representations, i.e. with a typeclass (may have to push ValueRepr to polynote-runtime)
    List(stringRepr)
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
      "^(scala|javax?|jdk|sun|com.sun|com.oracle|polynote|org.w3c|org.xml|org.omg|org.ietf|org.jcp|org.apache.spark|org.apache.hadoop|org.codehaus)\\.",
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