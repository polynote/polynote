package polynote.kernel.remote

import org.scalatest.{FreeSpec, Matchers}
import polynote.testing.ZIOSpec
import polynote.testing.MockSystem
import zio.ZIO

import java.io.File
import java.nio.file.{Files, Paths}

class DeploySparkSubmitSpec extends FreeSpec with Matchers with ZIOSpec {

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
