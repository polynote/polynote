package polynote.server.repository.format

import java.nio.file.Path
import java.util.ServiceLoader

import polynote.kernel.{BaseEnv, GlobalEnv}
import polynote.messages.Notebook
import polynote.server.repository.NotebookContent
import zio.blocking.{Blocking, effectBlocking}
import zio.{RIO, ZIO}

import scala.collection.JavaConverters._

trait NotebookFormat {
  /**
    * Denotes the extension this format can decode. This must be unique.
    */
  def extension: String

  def handlesExt(path: Path): Boolean = path.toString.toLowerCase().endsWith(s".$extension")

  def decodeNotebook(noExtPath: String, rawContent: String): RIO[BaseEnv with GlobalEnv, Notebook]
  
  def encodeNotebook(notebook: NotebookContent): RIO[BaseEnv with GlobalEnv, String]
}

object NotebookFormat {
  private lazy val unsafeLoad = ServiceLoader.load(classOf[NotebookFormat]).iterator.asScala.toList
  def load: RIO[Blocking, List[NotebookFormat]] = effectBlocking(unsafeLoad)

  def isSupported: RIO[Blocking, Path => Boolean] = for {
    providers <- load
  } yield {
    p: Path => providers.exists(_.handlesExt(p))
  }

  def isSupported1(path: Path): RIO[Blocking, Boolean] = isSupported.map(f => f(path))

  def getFormat(path: Path): RIO[Blocking, NotebookFormat] = for {
    providers <- load
    fmt <- ZIO.succeed(providers.find(_.handlesExt(path)))
      .someOrFail(new Exception(s"Unable to find notebook format provider for path $path. Available providers are ${providers.map(_.getClass)}"))
  } yield fmt
}