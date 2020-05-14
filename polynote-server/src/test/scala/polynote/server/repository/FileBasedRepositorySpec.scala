package polynote.server.repository

import java.io.{ByteArrayOutputStream, File, FileNotFoundException, OutputStream}
import java.nio.channels.SeekableByteChannel
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.ConcurrentHashMap

import org.scalamock.scalatest.MockFactory
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FreeSpec, Matchers}
import polynote.kernel.BaseEnv
import polynote.messages.{Notebook, ShortList}
import polynote.server.MockServerSpec
import polynote.server.repository.format.NotebookFormat
import polynote.server.repository.fs.{NotebookFile, NotebookFilesystem, WAL}
import zio.{RIO, RManaged, ZIO, ZManaged}
import zio.blocking.effectBlocking

import scala.collection.JavaConverters._

class FileBasedRepositorySpec extends FreeSpec with Matchers with BeforeAndAfterEach with MockFactory with MockServerSpec {

  private val tmpDir = Paths.get("/notebooks/")

  private val tmpFS = new NotebookFilesystem {

    private val notebooks = new ConcurrentHashMap[Path, String]()

    private class File(path: Path) extends NotebookFile {
      override def overwrite(content: String): RIO[BaseEnv, Unit] = ZIO.effectTotal(notebooks.put(path, content))
      override def readContent(): RIO[BaseEnv, String] = ZIO.effectTotal(Option(notebooks.get(path))).someOrFail(new FileNotFoundException())
      override def close(): RIO[BaseEnv, Unit] = ZIO.unit
    }

    override def readPathAsString(path: Path): RIO[BaseEnv, String] = withKeyContent(path)((_, content) => content)

    override def openNotebookFile(path: Path): RIO[BaseEnv, NotebookFile] = ZIO.succeed(new File(path))

    override def writeStringToPath(path: Path, content: String): RIO[BaseEnv, Unit] = ZIO(notebooks.put(tmpDir.resolve(path), content))

    override def createLog(path: Path): RIO[BaseEnv, WAL.WALWriter] = ZIO.succeed(WAL.WALWriter.NoWAL)

    override def list(path: Path): RIO[BaseEnv, List[Path]] = ZIO(notebooks.keys().asScala.toList)

    override def validate(path: Path): RIO[BaseEnv, Unit] = ZIO.unit  // should be mocked as needed by the test

    def clear(): Unit = notebooks.clear()

    override def exists(path: Path): RIO[BaseEnv, Boolean] = ZIO(notebooks.containsKey(tmpDir.resolve(path)))

    override def move(from: Path, to: Path): RIO[BaseEnv, Unit] = withKeyContent(from) {
      (fromKey, content) =>
        val toKey = tmpDir.resolve(to)
        notebooks.put(toKey, content)
        notebooks.remove(fromKey)
    }

    override def copy(from: Path, to: Path): RIO[BaseEnv, Unit] = withKeyContent(from) {
      (_, content) =>
        val toKey = tmpDir.resolve(to)
        notebooks.put(toKey, content)
    }

    override def delete(path: Path): RIO[BaseEnv, Unit] = ZIO(notebooks.remove(tmpDir.resolve(path)))

    override def init(path: Path): RIO[BaseEnv, Unit] = ZIO.unit

    private def withKeyContent[T](k: Path)(f: (Path, String) => T) = ZIO {
      val key = tmpDir.resolve(k)
      f(key, notebooks.get(key))
    }
  }
  private val repo = new FileBasedRepository(tmpDir, fs = tmpFS)

  override def beforeEach(): Unit = {
    tmpFS.clear()
    super.beforeEach()
  }

  private def emptyNB(path: String) = Notebook(path, ShortList(List.empty), None)

  "A FileBasedRepository" - {
    "should roundtrip the notebook if the format exists" in {
      val nb = emptyNB("somePath.ipynb")
      repo.saveNotebook(nb).runIO
      repo.loadNotebook(nb.path).runIO shouldEqual nb
    }

    "should fail to save the notebook if the format can't be found" in {
      val nb = emptyNB("somePath.unknown")
      an[Exception] should be thrownBy repo.saveNotebook(nb).runIO
    }

    "should list all available, supported notebooks" in {
      // initialize notebooks
      val validNBs = List("validNB1.ipynb", "validNB2.ipynb", "validNB3.ipynb")
      val invalidNBs = List("invalidNB1.unknown", "invalidNB2.unknown")
      (validNBs ++ invalidNBs).foreach { path =>
        tmpFS.writeStringToPath(Paths.get(path), "").runIO
      }

      repo.listNotebooks().runIO should contain theSameElementsAs(validNBs)
    }

    "should correctly check whether notebooks exist" in {
      tmpFS.writeStringToPath(Paths.get("foo.ipynb"), "").runIO

      repo.notebookExists("foo.ipynb").runIO shouldBe true
      repo.notebookExists("doesnotexist.ipynb").runIO shouldBe false
    }

    "should generate a URI for existing notebooks" in {
      tmpFS.writeStringToPath(Paths.get("foo.ipynb"), "").runIO

      repo.notebookURI("foo.ipynb").runIO shouldEqual Option(tmpDir.resolve("foo.ipynb").toUri)
      repo.notebookURI("doesnotexist.ipynb").runIO shouldBe empty
    }

    "should generate unique notebook names when paths collide" in {
      val namesAndResults = Seq(
        ("foo.ipynb", "foo.ipynb"),
        ("foo.ipynb", "foo2.ipynb"),
        ("foo2.ipynb", "foo3.ipynb"),
        ("foo100.ipynb", "foo100.ipynb"),
        ("foo100.ipynb", "foo101.ipynb"),
        ("foo100.ipynb", "foo102.ipynb"),
        ("foo.ipynb", "foo4.ipynb"),
        ("bar1.ipynb", "bar1.ipynb"),
        ("bar1.ipynb", "bar2.ipynb"),
        ("bar1.ipynb", "bar3.ipynb")
      ) ++ (1 to 100).map(i => "notebook1.ipynb" -> s"notebook$i.ipynb")

      namesAndResults.foreach {
        case (name, result) =>
          val generatedName = repo.findUniqueName(name).runIO
          generatedName shouldEqual result
          // generate collision for next time
          tmpFS.writeStringToPath(Paths.get(generatedName), "").runIO
      }
    }

    "should create empty notebooks" in {
      val name = "my_nb.ipynb"
      repo.createNotebook(name).runIO shouldEqual name
      repo.loadNotebook(name).runIO shouldEqual repo.emptyNotebook(name, "my nb").runIO
    }

    "should create notebooks with content" in {
      val nb = repo.emptyNotebook("foo.ipynb", "my title").runIO

      val nbString = for {
        fmt       <- NotebookFormat.getFormat(Paths.get(nb.path))
        rawString <- fmt.encodeNotebook(NotebookContent(nb.cells, nb.config))
      } yield rawString

      repo.createNotebook(nb.path, Option(nbString.runIO)).runIO shouldEqual nb.path
      repo.loadNotebook(nb.path).runIO shouldEqual nb
    }

    "should rename notebooks" in {
      tmpFS.writeStringToPath(Paths.get("foo.ipynb"), "foo").runIO
      repo.notebookExists("foo.ipynb").runIO shouldBe true

      repo.renameNotebook("foo.ipynb", "bar.ipynb").runIO

      repo.notebookExists("foo.ipynb").runIO shouldEqual false
      repo.notebookExists("bar.ipynb").runIO shouldEqual true
      tmpFS.readPathAsString(Paths.get("bar.ipynb")).runIO shouldEqual "foo"
    }

    "should copy notebooks" in {
      tmpFS.writeStringToPath(Paths.get("foo.ipynb"), "foo").runIO

      repo.copyNotebook("foo.ipynb", "bar.ipynb").runIO

      repo.notebookExists("foo.ipynb").runIO shouldEqual true
      repo.notebookExists("bar.ipynb").runIO shouldEqual true
      tmpFS.readPathAsString(Paths.get("bar.ipynb")).runIO shouldEqual "foo"
    }

    "should delete notebooks" in {
      tmpFS.writeStringToPath(Paths.get("foo.ipynb"), "foo").runIO
      repo.deleteNotebook("foo.ipynb").runIO
      tmpFS.readPathAsString(Paths.get("foo.ipynb")).runIO shouldEqual null // empty
    }
  }
}
