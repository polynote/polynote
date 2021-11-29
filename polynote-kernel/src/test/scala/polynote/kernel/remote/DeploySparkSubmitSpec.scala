package polynote.kernel.remote

import org.scalatest.{FreeSpec, Matchers}
import polynote.config.PolynoteConfig
import polynote.buildinfo.BuildInfo
import polynote.messages.{NotebookConfig, TinyList}
import polynote.testing.ZIOSpec
import polynote.testing.MockSystem
import zio.ZIO

import java.io.File
import java.net.{URI, URL}
import java.nio.file.{Files, Paths}

class DeploySparkSubmitSpec extends FreeSpec with Matchers with ZIOSpec {

  "Generates a proper command string" in {
    val conf = PolynoteConfig()
    val jvmProps: Option[TinyList[String]] = Option(List("-Dprop=value"))
    val nbConf = NotebookConfig(None, None, None, None, None, None, jvmArgs = jvmProps)
    val nbPath = "foo"
    val assemblyJar = "/path/to/polynote/polynote-spark-assembly.jar"
    val cp = Seq(
      URI.create("file:///path/to/my.jar").toURL,
      URI.create("file:///path/to/not-a-jar/").toURL,
      URI.create(s"file://${assemblyJar}").toURL)
    val serverArgs = List("--address", "blah", "--port", "12345")

    val expected =
      "spark-submit" ::
        "--class" :: "polynote.kernel.remote.RemoteKernelClient" ::
        "--name" :: s"Polynote ${BuildInfo.version}: $nbPath" ::
        "--driver-java-options" :: (jvmArgs(nbConf) ++ asPropString(javaOptions)).mkString(" ") ::
        "--driver-class-path" :: cp.map(_.getPath).mkString(File.pathSeparator) ::
        assemblyJar ::
        serverArgs

    val command = DeploySparkSubmit.build(conf, nbConf, nbPath, cp, serverArgs = serverArgs)

    command should contain theSameElementsInOrderAs expected
  }

  "Detect scala version" - {

    "from SPARK_HOME" - {
      "when directory exists" in {

        // create a temp SPARK_HOME directory
        val expectedVersion = "2.12"
        val sparkHome = Files.createTempDirectory("test_spark_home")
        val jars = Paths.get(sparkHome.toString, "jars").toFile
        jars.mkdirs()
        jars.deleteOnExit()
        val jar = new File(jars, s"scala-library-$expectedVersion.12.jar")
        jar.createNewFile()
        jar.deleteOnExit()

        val result = DeploySparkSubmit.detectFromSparkHome.provideLayer(
          baseLayer ++ MockSystem.layerOfEnvs("SPARK_HOME" -> sparkHome.toString)
        ).runIO()

        result shouldEqual Some(expectedVersion)
      }

      "when directory doesn't exist" in {
        val result = DeploySparkSubmit.detectFromSparkHome.provideLayer(
          baseLayer ++ MockSystem.layerOfEnvs("SPARK_HOME" -> "/dev/null/does_not_exist")
        ).runIO()

        result shouldEqual None
      }
    }

    def fromSparkSubmitWithSpark: Any = {
      // this test will only run when SPARK_HOME environment exists (which is true in CI). The results of
      // detectFromSparkHome and detectFromSparkSubmit should match in that case.

      val Some(fromSparkHome) = DeploySparkSubmit.detectFromSparkSubmit.runIO()
      val Some(fromSparkSubmit) = DeploySparkSubmit.detectFromSparkSubmit.runIO()
      fromSparkSubmit shouldEqual fromSparkHome
    }

    "from spark_submit --version" - {
      if (System.getenv("SPARK_HOME") != null) {
        "when spark is installed" in {
          fromSparkSubmitWithSpark
        }
      } else {
        "when spark is installed" ignore {}
      }
    }

  }

}
