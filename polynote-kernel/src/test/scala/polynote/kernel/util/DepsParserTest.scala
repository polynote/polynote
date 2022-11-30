package polynote.kernel.util

import org.scalamock.scalatest.MockFactory
import org.scalatest.{FreeSpec, Matchers, PrivateMethodTester}
import polynote.kernel.util.DepsParser.{flattenDeps, parseTxtDeps}
import polynote.testing.ConfiguredZIOSpec

import java.io.{File, FileWriter}
import java.net.URI
import java.nio.file.Paths

class DepsParserTest extends FreeSpec with Matchers with MockFactory with ConfiguredZIOSpec with PrivateMethodTester {
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

  "TxtParser" - {
    "parseTxtDeps" - {
      "correctly parses a list of jars in a .txt file" in {
        val uri = fileManager("someFileName.txt", "ex1.jar\nex2.jar")
        parseTxtDeps(uri).runIO() shouldBe List("ex1.jar", "ex2.jar")
      }

      "correctly parses extraneous whitespace in a list of jars in a .txt file" in {
        val uri = fileManager("someFileName.txt", "ex1.jar\n\n\n\n    ex2.jar")
        parseTxtDeps(uri).runIO() shouldBe List("ex1.jar", "ex2.jar")
      }

      "throws on a bad .txt file path" in {
        parseTxtDeps(new URI("badPath")).isFailure
      }
    }

    "flattenDeps" - {
      "correctly parses a list of dependencies including a .txt file" in {
        val uri = fileManager("someFileName.txt", "ex1.jar\nex2.jar").toString()
        flattenDeps(List("ex0.jar", uri, "ex3.jar")).runIO() shouldBe List("ex0.jar", "ex3.jar", "ex1.jar", "ex2.jar")
      }
    }
  }
}
