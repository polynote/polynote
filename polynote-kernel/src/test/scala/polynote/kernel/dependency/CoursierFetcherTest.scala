package polynote.kernel.dependency

import org.scalamock.scalatest.MockFactory
import org.scalatest.{FreeSpec, Matchers, PrivateMethodTester}
import polynote.kernel.dependency.CoursierFetcher.parseTxtDeps
import polynote.testing.ConfiguredZIOSpec

import java.io.{File, FileWriter}
import java.net.URI
import java.nio.file.Paths

class CoursierFetcherTest extends FreeSpec with Matchers with MockFactory with ConfiguredZIOSpec with PrivateMethodTester {
  def fileManager(fileName: String, content: String): URI = {
    // Create fake directory
    val dir = Paths.get("someDir").toFile
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

    file.toURI()
  }

  "CoursierFetcher" - {
    "parseTxtFile" - {
      "correctly parses a list of jars in a .txt file" - {
        val uri = fileManager("someFileName", "ex1.jar\nex2.jar")
        parseTxtDeps(uri).runIO() shouldBe List("ex1.jar", "ex2.jar")
      }

      "correctly parses extraneous whitespace in a list of jars in a .txt file" - {
        val uri = fileManager("someFileName", "ex1.jar\n\n\n\n    ex2.jar")
        parseTxtDeps(uri).runIO() shouldBe List("ex1.jar", "ex2.jar")
      }

      "throws on a bad .txt file path" - {
        parseTxtDeps(new URI("badPath")).isFailure
      }
    }
  }
}
