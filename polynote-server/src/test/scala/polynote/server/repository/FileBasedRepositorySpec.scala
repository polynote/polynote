package polynote.server.repository
import java.io.File
import java.nio.file.{FileAlreadyExistsException, Files, Path, Paths}

import cats.effect.{ContextShift, IO}
import org.scalatest.{FreeSpec, Matchers}
import polynote.config.PolynoteConfig
import polynote.messages.Notebook

import scala.concurrent.ExecutionContext

class FileBasedRepositorySpec extends FreeSpec with Matchers {

  "A FileBasedRepo" - {
    "when creating a notebook" - {
      "should create it if it doesn't already exist" in {
        val repo = new SimpleFileBasedRepo

        Seq("foo", "bar.blah", "bar.test", "a/b/c/d", "/banana").foreach { nbName =>
          val resultingPath = repo.createNotebook(nbName).unsafeRunSync()

          repo.notebookExists(resultingPath).unsafeRunSync() shouldBe true
          val abspath = repo.path.resolve(resultingPath)
          Files.exists(abspath) shouldBe true
        }
      }

      "should error if the notebook already exists" in {

        val repo = new SimpleFileBasedRepo

        val nbName = "foo"
        val resultingPath = repo.createNotebook(nbName).unsafeRunSync()

        repo.notebookExists(resultingPath).unsafeRunSync() shouldBe true
        Files.exists(repo.path.resolve(resultingPath)) shouldBe true

        a[FileAlreadyExistsException] should be thrownBy {
          repo.createNotebook(nbName).unsafeRunSync()
        }
      }

      "should create intermediate directories as necessary" in {
        val repo = new SimpleFileBasedRepo

        val nbName = "foo/bar/baz/quux"
        val resultingPath = repo.createNotebook(nbName).unsafeRunSync()

        repo.notebookExists(resultingPath).unsafeRunSync() shouldBe true
        Files.exists(repo.path.resolve(resultingPath)) shouldBe true

        def check(p: Path): Boolean = {
          if (p == repo.path) true
          else {
            if (p.toFile.isDirectory) {
              Option(p.getParent) match {
                case Some(dir) => Files.exists(dir) && check(dir)
                case None => false // uh oh we got to root unexpectedly
              }
            } else Files.exists(p)
          }
        }

        check(repo.path.resolve(resultingPath)) shouldBe true
      }

      "should load an existing notebook" in {
        val repo = new SimpleFileBasedRepo

        val nbName = "foo"
        val resultingPath = repo.createNotebook(nbName).unsafeRunSync()

        repo.notebookExists(resultingPath).unsafeRunSync() shouldBe true

        repo.loadString(s"$nbName.test").unsafeRunSync() should (include("foo") and include("This is a text cell"))
      }

      "should validate notebook paths by extension" in {
        val repo = new SimpleFileBasedRepo

        repo.validNotebook(Paths.get("foo")) shouldBe false
        repo.validNotebook(Paths.get("foo.hs")) shouldBe false
        repo.validNotebook(Paths.get("foo.test")) shouldBe true
      }

      "should validate notebook creation with maxDepth" in {
        val repo = new SimpleFileBasedRepo

        an[IllegalArgumentException] shouldBe thrownBy {
          repo.createNotebook((1 to repo.maxDepth + 1).mkString("/")).unsafeRunSync()
        }
      }

      "should list all available notebooks" in {

        val repo = new SimpleFileBasedRepo

        val nbs = Seq("foo", "bar.blah", "bar/test", "a/b/c/d", "banana", (1 to repo.maxDepth).mkString("/"))

        nbs.foreach { nbName =>
          repo.createNotebook(nbName).unsafeRunSync()
        }

        val extra = Files.createDirectories(repo.path.resolve((1 until repo.maxDepth).mkString("/"))).resolve("visible.test")
        Files.createFile(extra)

        val tooDeep = Files.createDirectories(repo.path.resolve((1 to repo.maxDepth).mkString("/"))).resolve("hidden.test")
        Files.createFile(tooDeep)

        repo.listNotebooks().unsafeRunSync() should contain theSameElementsAs nbs.map(_ + ".test") :+ repo.path.relativize(extra).toString
      }

      "should roundtrip raw notebooks" in {
        val repo = new SimpleFileBasedRepo

        val path = "bananas"
        val content = "B! A! N! A! N! A! S!"

        val loc = repo.createNotebook(path, Option(content)).unsafeRunSync()
        loc shouldEqual "bananas.test"

        repo.loadString(loc).unsafeRunSync() shouldEqual content
      }
    }
  }

}

class SimpleFileBasedRepo extends FileBasedRepository {
  val tmp: Path = Files.createTempDirectory(getClass.getSimpleName)
  val config: PolynoteConfig = PolynoteConfig()

  sys.addShutdownHook(cleanup())

  def cleanup() = {
    def delete(file: File): Unit = {
      file.listFiles().foreach {
        case f if f.isDirectory =>
          delete(f)
        case p => Files.delete(p.toPath)
      }
    }
    delete(tmp.toFile)
  }

  override def path: Path = tmp

  override def chunkSize: Int = 8192

  override def executionContext: ExecutionContext = ExecutionContext.global

  implicit val contextShift: ContextShift[IO] = IO.contextShift(executionContext)

  override protected def defaultExtension: String = "test"

  override def loadNotebook(path: String): IO[Notebook] = ???

  override def saveNotebook(path: String, notebook: Notebook): IO[Unit] = writeString(path, notebook.cells.map(_.content).mkString)

  // visible for testing
  override def loadString(path: String): IO[String] = super.loadString(path)
  override def validNotebook(path: Path): Boolean = super.validNotebook(path)
  override def maxDepth: Int = super.maxDepth
}