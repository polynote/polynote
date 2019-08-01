package polynote.kernel

import java.io.File
import java.net.URLDecoder
import java.nio.file.{FileSystems, Files, Path, StandardCopyOption}
import java.util.Collections

import cats.data.OptionT
import cats.effect.IO
import cats.effect.concurrent.Deferred
import cats.syntax.apply._
import cats.syntax.flatMap._
import org.apache.spark.SparkEnv
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.thief.DAGSchedulerThief
import polynote.config.PolynoteConfig
import polynote.kernel.PolyKernel._
import polynote.kernel.dependency.DependencyProvider
import polynote.kernel.lang.LanguageInterpreter
import polynote.kernel.util.{KernelContext, Publish}
import polynote.messages._

import scala.reflect.io.{AbstractFile, Directory, PlainDirectory}
import scala.tools.nsc.{Settings, io}

// TODO: Should the spark init stuff go into the Spark Scala kernel? That way PolyKernel could be the only Kernel.
class SparkPolyKernel(
  getNotebook: () => IO[Notebook],
  ctx: KernelContext,
  dependencyProviders: Map[String, DependencyProvider],
  statusUpdates: Publish[IO, KernelStatusUpdate],
  outputPath: Path,
  subKernels: Map[String, LanguageInterpreter.Factory[IO]] = Map.empty,
  parentClassLoader: ClassLoader,
  config: PolynoteConfig
) extends PolyKernel(getNotebook, ctx, new PlainDirectory(new Directory(outputPath.toFile)), dependencyProviders, statusUpdates, subKernels, config) {

  import kernelContext.global

  val polynoteRuntimeJar = "polynote-runtime.jar"
  val polynoteSparkRuntimeJar = "polynote-spark-runtime.jar"

  private def runtimeJars(tmp: Path) = {
    def file(name: String) = {
      val resourceURL = getClass.getClassLoader.getResource(name)
      resourceURL.getProtocol match {
        case "jar" =>
          val jarFS = FileSystems.newFileSystem(
            resourceURL.toURI,
            Collections.emptyMap[String, Any]
          )
          val inPath = jarFS.getPath(name)
          val runtimeJar = new File(tmp.toFile, name).toPath
          Files.copy(inPath, runtimeJar, StandardCopyOption.REPLACE_EXISTING)
          jarFS.close()
          runtimeJar

        case "file" =>
          new File(resourceURL.getPath).toPath
      }
    }

    file(polynoteRuntimeJar) :: file(polynoteSparkRuntimeJar) :: Nil
  }

  private lazy val dependencyJars = {
    // dependencies which have characters like "+" in them get mangled... de-mangle them into a temp directory for adding to spark
    val tmp = Files.createTempDirectory("dependencies")
    tmp.toFile.deleteOnExit()

    val jars = for {
      namedFiles <- dependencyProviders.get("scala").map(_.dependencies).toList
      (_, file) <- namedFiles if file.getName endsWith ".jar"
    } yield file -> tmp.resolve(URLDecoder.decode(file.getName, "utf-8"))

    runtimeJars(tmp) ::: jars.map {
      case (file, path) =>
        val copied = Files.copy(file.toPath, path)
        copied.toFile.deleteOnExit()
        copied.toUri.toURL
    }
  }

  private val realOutputPath = Deferred.unsafe[IO, AbstractFile]

  // initialize the session, and add task listener
  private lazy val sparkListener = new KernelListener(statusUpdates)

  private lazy val session: SparkSession = {
    val nbSparkConfig = getNotebook().unsafeRunSync().config.flatMap(_.sparkConfig)
    val conf = org.apache.spark.repl.Main.conf

    // we set everything that is in the notebook spark config and use the server spark config as a backup
    val sparkConfig = config.spark ++ nbSparkConfig.getOrElse(Map.empty)

    sparkConfig.foreach {
      case (k, v) => conf.set(k, v)
    }

    conf.setIfMissing("spark.master", "local[*]")
    if (conf.get("spark.master") == "local[*]") {
      conf.set("spark.driver.host", "127.0.0.1")
    }
    conf.setJars(dependencyJars.map(_.toString))
    conf.set("spark.repl.class.outputDir", outputPath.toString)
    conf.setAppName("Polynote session")

    // TODO: experimental
    //    conf.set("spark.driver.userClassPathFirst", "true")
    //    conf.set("spark.executor.userClassPathFirst", "true")
    // TODO: experimental

    Thread.currentThread().setContextClassLoader(kernelContext.classLoader)
    val sess = org.apache.spark.repl.Main.createSparkSession()
    SparkEnv.get.serializer.setDefaultClassLoader(kernelContext.classLoader)

    // for some reason the jars aren't totally working...

    // If the session was already created, then we're sharing with another notebook. We have to put our classes in their
    // output dir, and also late-add our dependencies
    // TODO: better way to handle this?
    if (sess.conf.get("spark.repl.class.outputDir") != outputPath.toString) {
      val realOutputPathStr = sess.conf.get("spark.repl.class.outputDir")
      // TODO: should have some way to push arbitrary user-facing messages (warnings etc) to the frontend
      logger.info(s"Spark session is shared with another notebook; changing compiler target directory to $realOutputPathStr. Dependencies are late-added, and shared with existing notebook sessions (this can cause problems!)")

      val dir = new PlainDirectory(new Directory(new File(realOutputPathStr)))
      realOutputPath.complete(dir).unsafeRunSync()
      global.settings.outputDirs.setSingleOutput(dir)

      dependencyJars.map(_.toString).foreach(sess.sparkContext.addJar)
    } else realOutputPath.complete(outputDir).unsafeRunSync()

    sess.sparkContext.addSparkListener(sparkListener)
    sess
  }

  override def init(): IO[Unit] = super.init() >> taskManager.runTaskIO("spark", "Spark session", "Starting Spark session...") {
    taskInfo => IO(session).handleErrorWith(err => IO(logger.error(err)(err.getMessage)) *> IO.raiseError(err)) >> info.flatMap { update =>
      update.map(statusUpdates.publish1).getOrElse(IO.unit)
    }
  }

  override def info: IO[Option[KernelInfo]] = {
    val sparkKI = OptionT(IO(session).map { sess =>
      sess.sparkContext.uiWebUrl.map { url =>
        KernelInfo(TinyMap(ShortString("Spark Web UI:") -> s"""<a href="$url" target="_blank">$url</a>"""))
      }
    })
    val superKI = OptionT(super.info)

    sparkKI.map2(superKI)((spk, sup) => spk.combine(sup)).orElse(sparkKI).orElse(superKI).value
  }

  override def cancelTasks(): IO[Unit] = super.cancelTasks() *> IO(DAGSchedulerThief(session).cancelAllJobs())

  override def shutdown(): IO[Unit] = super.shutdown() *> contextShift.evalOn(ctx.executionContext)(IO(session.stop()))
}

object SparkPolyKernel {
  def apply(
    getNotebook: () => IO[Notebook],
    dependencies: Map[String, DependencyProvider],
    subKernels: Map[String, LanguageInterpreter.Factory[IO]],
    statusUpdates: Publish[IO, KernelStatusUpdate],
    extraClassPath: List[File] = Nil,
    baseSettings: Settings = defaultBaseSettings,
    parentClassLoader: ClassLoader = defaultParentClassLoader,
    config: PolynoteConfig
  ): SparkPolyKernel = {

    val notebookFilename = getNotebook().map {
      nb => new File(nb.path).getName
    }.unsafeRunSync()

    val outputPath = org.apache.spark.repl.Main.outputDir.toPath
    outputPath.toFile.deleteOnExit()

    val outputDir = new PlainDirectory(new Directory(outputPath.toFile))

    val sparkClasspath = System.getProperty("java.class.path")
      .split(File.pathSeparatorChar)
      .view
      .map(new File(_))
      .filter(file => io.AbstractFile.getURL(file.toURI.toURL) != null)

    val kernelContext = KernelContext(dependencies, statusUpdates, baseSettings, extraClassPath ++ sparkClasspath, outputDir, parentClassLoader)

    val kernel = new SparkPolyKernel(
      getNotebook,
      kernelContext,
      dependencies,
      statusUpdates,
      outputPath,
      subKernels,
      parentClassLoader,
      config
    )

    //global.extendCompilerClassPath(kernel.classPath: _*)

    kernel
  }

}