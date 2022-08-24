package polynote.server

import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers
import org.scalatest.FreeSpec
import polynote.config.{Behavior, KernelIsolation, PolynoteConfig}
import polynote.server.SocketSession.{getMaybeContent, readFromTemplatePath}
import polynote.testing.ConfiguredZIOSpec

import java.io.{File, FileWriter}
import java.nio.file.Paths

class SocketSessionSpec extends FreeSpec with Matchers with MockFactory with ConfiguredZIOSpec {
  val (goodDir, anotherGoodDir) = ("goodDir", "anotherGoodDir")
  val (goodContent, anotherGoodContent) = ("goodContent", "anotherGoodContent")
  val fileName = "test.ipynb"
  val (goodPath, anotherGoodPath) = (s"$goodDir/$fileName", s"$anotherGoodDir/$fileName")

  override def config: PolynoteConfig = PolynoteConfig(behavior = Behavior(true, KernelIsolation.Always, Nil, List(goodPath, anotherGoodPath)))

  def fileManager(directoryName: String, fileName: String, content: String): Unit = {
    // Create fake directory
    val dir = Paths.get(directoryName).toFile
    dir.mkdirs()
    dir.deleteOnExit()

    // Create fake file
    val file = new File(dir, fileName)
    file.createNewFile()
    file.deleteOnExit()

    // Write to fake file
    val fw = new FileWriter(file)
    fw.write(content)
    fw.close()
  }

  "SocketSession" - {
    "getMaybeContent" - {
      "returns maybeContent with some maybeContent" in {
        getMaybeContent(Some("maybeContent"), None).runIO() shouldBe Some("maybeContent")
      }

      "returns content at maybeTemplatePath with valid maybeTemplatePath" in {
        fileManager(goodDir, fileName, goodContent)
        getMaybeContent(None, Some(goodPath)).runIO() shouldBe Some(goodContent)

        fileManager(anotherGoodDir, fileName, anotherGoodContent)
        getMaybeContent(None, Some(anotherGoodPath)).runIO() shouldBe Some(anotherGoodContent)
      }

      "fails with bad maybeTemplatePath" in {
        getMaybeContent(None, Some("a/bad/path")).isFailure
      }
    }

    "readFromTemplatePath" - {
      "returns file contents with valid templatePath" in {
        fileManager(goodDir, fileName, goodContent)
        readFromTemplatePath(goodPath).runIO() shouldBe Some(goodContent)

        fileManager(anotherGoodDir, fileName, anotherGoodContent)
        readFromTemplatePath(anotherGoodPath).runIO() shouldBe Some(anotherGoodContent)
      }

      "fails with bad templatePath" in {
        readFromTemplatePath("a/bad/path").isFailure
      }
    }
  }
}
