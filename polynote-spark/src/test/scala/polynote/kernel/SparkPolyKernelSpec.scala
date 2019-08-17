package polynote.kernel

import java.io.{File, FileOutputStream}
import java.nio.file.{Files}
import java.util.concurrent.Executors
import java.util.jar.JarOutputStream

import cats.effect.{ContextShift, IO}
import fs2.concurrent.Topic
import org.apache.spark.sql.SparkSession
import org.scalatest.{FlatSpec, Matchers}
import polynote.config.PolynoteConfig
import polynote.kernel.dependency.ClassLoaderDependencyProvider
import polynote.kernel.lang.MockCLDepProvider
import polynote.messages.{Notebook, ShortList}

import scala.concurrent.ExecutionContext

class SparkPolyKernelSpec extends FlatSpec with Matchers {

  implicit val contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.fromExecutorService(Executors.newCachedThreadPool()))

  "SparkPolyKernel" should "properly handle spark dependencies" in {
    val dependencies = List("foo.jar", "foo+bar.jar", "foo bar.jar", "foo-bar.jar")
    val tmpDir = Files.createTempDirectory("deps")
    val depFiles = dependencies.map {
      dep =>
        val tmpFile = tmpDir.resolve(dep).toFile
        val jarFile = new JarOutputStream(new FileOutputStream(tmpFile))
        jarFile.close() // just an empty jar
        (dep, tmpFile)
    }
    val depMgr = new ClassLoaderDependencyProvider(depFiles)

    val sparkPolyKernel =
      Topic[IO, KernelStatusUpdate](UpdatedTasks(Nil)).flatMap {
        topic =>
          SparkPolyKernel(
            () => IO.pure(Notebook("foo", ShortList(Nil), None)),
            Map("scala" -> depMgr),
            Map.empty,
            topic,
            config = PolynoteConfig()
          )
      }.unsafeRunSync()
    val deps = sparkPolyKernel.dependencyJars
    val sparkJars = sparkPolyKernel.session.sparkContext.jars
    deps.map(_.toString) shouldEqual sparkJars

    val justDepJars = deps.map(d => new File(d.getPath).getName)
    val expectedDepJars = "foo.jar" :: "foo_bar.jar" :: "foo bar.jar" :: "foo-bar.jar" :: sparkPolyKernel.polynoteRuntimeJars
    justDepJars should contain theSameElementsAs expectedDepJars
  }

}
