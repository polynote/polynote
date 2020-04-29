package polynote

import java.nio.file.{Path, Paths}
import java.time.{Instant, OffsetDateTime, ZoneOffset}

import org.scalamock.scalatest.MockFactory
import org.scalatest.{FreeSpec, Matchers}
import polynote.app.Args
import polynote.config.PolynoteConfig
import polynote.kernel.environment.PublishResult
import polynote.kernel.{Kernel, Output, Result}
import polynote.kernel.interpreter.Interpreter
import polynote.messages.{CellID, Notebook, NotebookCell, ShortList}
import polynote.server.AppEnv
import polynote.server.repository.{NotebookContent, NotebookRepository}
import polynote.server.repository.format.NotebookFormat
import polynote.server.repository.format.ipynb.IPythonFormat
import polynote.server.repository.fs.{FileSystems, NotebookFilesystem}
import polynote.testing.ZIOSpec
import polynote.testing.ZIOSpecBase.BaseEnv
import zio.clock.Clock
import zio.{ZEnv, ZIO, ZLayer}

class NotebookRunnerSpec extends FreeSpec with ZIOSpec with MockFactory with Matchers with org.scalamock.matchers.Matchers {

  private val config = PolynoteConfig()
  private val configLayer = ZLayer.succeed(config)
  private val fs = mock[NotebookFilesystem]
  private val fsLayer = ZLayer.succeed(FileSystems.local(fs))
  private val repo = mock[NotebookRepository]
  private val repoLayer = ZLayer.succeed(repo)
  private val kernel = mock[Kernel]
  private val kernelFactory = Kernel.Factory.const(kernel)
  private val kernelFactoryLayer = ZLayer.succeed(kernelFactory)
  private val clock = mock[Clock.Service]
  private val clockLayer = ZLayer.succeed(clock)

  private val exampleNotebook = NotebookContent(
    List(
      NotebookCell(CellID(0), "text", "Some text"),
      NotebookCell(CellID(1), "scala", """println("hi")"""),
      NotebookCell(CellID(2), "vega", "this shouldn't be looked at, because we can't locally interpret vega") // TODO: when headless vega interpreter is available, change this to verify
    ), None
  ).toNotebook("doesn't matter")

  private def encodeNotebook(notebook: Notebook) =
    new IPythonFormat().encodeNotebook(NotebookContent(notebook.cells, notebook.config)).runIO()

  private val exampleNotebookStr = encodeNotebook(exampleNotebook)

  private def env(args: String*): ZLayer[ZEnv, Throwable, AppEnv] =
    Args.parse(args.toList) ++
      (baseLayer >>> Interpreter.Factories.load) ++
      configLayer ++ fsLayer ++ repoLayer ++ kernelFactoryLayer ++
      baseLayer ++ envLayer ++ clockLayer

  private def runMain(args: String*) = NotebookRunner.main
    .mapError(MainDiedException(_))
    .provideSomeLayer[ZEnv](env(args: _*)).runIO()

  private val exampleOutputs: ShortList[Result] = ShortList(List(Output("text/plain; rel=stdout", "hi")))

  private val updatedNotebook = exampleNotebook.updateCell(CellID(1))(_.copy(results = exampleOutputs))
  private val updatedNotebookStr = encodeNotebook(updatedNotebook)

  private val fakeTime = OffsetDateTime.of(2019, 1, 14, 9, 51, 0, 0, ZoneOffset.ofHours(-8))


  private def setupKernel(): Unit = {
    (clock.currentDateTime _) expects () returning ZIO.succeed(fakeTime) anyNumberOfTimes()
    (kernel.init _) expects () returning ZIO.unit
    (kernel.queueCell _) expects (CellID(1)) returning ZIO.environment[PublishResult].map(env => PublishResult(exampleOutputs).provide(env))
    (kernel.shutdown _) expects () returning ZIO.unit
    ()
  }

  "NotebookRunner" - {
    "with --overwrite" - {
      "original notebook file is overwritten" - {
        "when notebook is a filesystem path" in {
          val path = Paths.get("./foo/bar.ipynb").toAbsolutePath
          (fs.readPathAsString _) expects (path) returning ZIO.succeed(exampleNotebookStr)
          setupKernel()
          (fs.writeStringToPath _) expects (path, updatedNotebookStr) returning ZIO.unit
          runMain("--overwrite", "./foo/bar.ipynb")
        }

        "when notebook is a repository path" in {
          val path = "foo/bar.ipynb"
          (repo.loadNotebook _) expects (path) returning ZIO.succeed(exampleNotebook)
          setupKernel()
          (repo.saveNotebook _) expects (updatedNotebook.copy(path = path)) returning ZIO.unit
          runMain("--overwrite", "foo/bar.ipynb")
        }
      }
    }

    "with output specifier" - {
      // roughly Polynote's open source birthday
      val fakeDatestamp = "20190114"
      val fakeTimestamp = "20190114175100"

      def expectPaths(inPath: String, expectedOutPath: String)(args: String*): Unit = {
        "when notebook is a filesystem path" in {
          (fs.readPathAsString _) expects (Paths.get(s"./$inPath").toAbsolutePath) returning ZIO.succeed(exampleNotebookStr)
          setupKernel()
          (fs.writeStringToPath _) expects (Paths.get(s"./$expectedOutPath").toAbsolutePath, updatedNotebookStr) returning ZIO.unit
          runMain(args :+ s"./$inPath": _*)
        }

        "when notebook is a repository path" in {
          (repo.loadNotebook _) expects (inPath) returning ZIO.succeed(exampleNotebook)
          setupKernel()
          (repo.saveNotebook _) expects (updatedNotebook.copy(path = expectedOutPath)) returning ZIO.unit
          runMain(args :+ inPath: _*)
        }
      }

      "append-datestamp" - {
        expectPaths("foo/bar.ipynb", s"foo/bar-$fakeDatestamp.ipynb")("--output", "append-datestamp")
      }

      "append-timestamp" - {
        expectPaths("foo/bar.ipynb", s"foo/bar-$fakeTimestamp.ipynb")("--output", "append-timestamp")
      }

      "append-const" - {
        expectPaths("foo/bar.ipynb", "foo/bar-flibberty.ipynb")("--output", "append-const", "-flibberty")
      }

      "fails for other" in {
        a [MainDiedException] should be thrownBy {
          runMain("--output", "lolwut", "foo/bar.ipynb")
        }
      }
    }
  }


  case class MainDiedException(err: String) extends RuntimeException(err)
}
