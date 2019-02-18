package polynote.kernel.context

import java.io.File
import java.net.URL

import cats.effect.IO
import polynote.kernel.lang.LanguageKernel
import polynote.kernel.util._

import scala.reflect.ClassTag
import scala.reflect.internal.util.ScalaClassLoader.URLClassLoader
import scala.reflect.internal.util.{AbstractFileClassLoader, BatchSourceFile}
import scala.reflect.io.{AbstractFile, VirtualDirectory}
import scala.tools.nsc.Settings
import scala.tools.nsc.interactive.Global

final case class GlobalInfo(global: Global, classPath: List[File], classLoader: AbstractFileClassLoader) {

  type GlobalProxy = global.type
  import global.{Symbol, TermName, Type}

  private val runtimeMirror = scala.reflect.runtime.universe.runtimeMirror(classLoader)
  private val importFromRuntime = global.internal.createImporter(scala.reflect.runtime.universe)

  private def typeOf(value: Any, staticType: Option[Type]): Type = staticType.getOrElse {
    try {
      importFromRuntime.importType {
        runtimeMirror.reflect(value).symbol.asType.toType
      }
    } catch {
      case err: Throwable => global.NoType
    }
  }

  def formatType(typ: global.Type): String = typ match {
    case mt @ global.MethodType(params: List[Symbol], result: Type) =>
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

  sealed case class RuntimeValue(
    name: String,
    value: Any,
    scalaType: global.Type,
    source: Option[LanguageKernel[IO]],
    sourceCellId: String
  ) extends SymbolDecl[IO] {
    lazy val typeString: String = formatType(scalaType)
    lazy val valueString: String = value.toString match {
      case str if str.length > 64 => str.substring(0, 64)
      case str => str
    }

    // don't need to hash everything to determine hash code; name collisions are less common than hash comparisons
    override def hashCode(): Int = name.hashCode()

    override def scalaType(g: Global): g.Type = if (g eq global) {
      g.typeOf[scalaType.type] // will this work...?
    } else throw new Exception("should never happen yikes !!!!")

    override def getValue: Option[Any] = Option(value)
  }

  object RuntimeValue {
    def apply(name: String, value: Any, source: Option[LanguageKernel[IO]], sourceCell: String): RuntimeValue = RuntimeValue(
      name, value, typeOf(value, None), source, sourceCell
    )
  }
}

object GlobalInfo {
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
  ): GlobalInfo = apply(dependencies, defaultBaseSettings, extraClassPath, defaultOutputDir, defaultParentClassLoader)

  def apply(
    dependencies: Map[String, List[(String, File)]],
    baseSettings: Settings,
    extraClassPath: List[File],
    outputDir: AbstractFile,
    parentClassLoader: ClassLoader
  ): GlobalInfo = {

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

    GlobalInfo(global, classPath, notebookClassLoader)
  }
}

/**
  * A symbol defined in a notebook cell
  */
trait SymbolDecl[F[_]] {
  def name: String
  def source: Option[LanguageKernel[F]]
  def sourceCellId: String
  def scalaType(g: Global): g.Type
  def getValue: Option[Any]
}
