package polynote.kernel.util

import java.io.File
import java.net.URL

import scala.reflect.internal.util.{AbstractFileClassLoader, BatchSourceFile}
import scala.reflect.internal.util.ScalaClassLoader.URLClassLoader
import scala.reflect.io.{AbstractFile, VirtualDirectory}
import scala.tools.nsc.Settings
import scala.tools.nsc.interactive.Global

final case class GlobalInfo(global: Global, classPath: List[File], classLoader: AbstractFileClassLoader)

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
    val requiredPaths = List(pathOf(classOf[List[_]]), pathOf(polynote.runtime.Runtime.getClass), pathOf(classOf[scala.reflect.runtime.JavaUniverse]), pathOf(classOf[scala.tools.nsc.Global])).map {
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