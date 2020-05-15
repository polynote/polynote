package polynote.server.repository.format.ipynb

import cats.syntax.either._
import io.circe.Printer
import io.circe.parser.parse
import io.circe.syntax._
import polynote.kernel.{BaseEnv, GlobalEnv}
import polynote.messages.Notebook
import polynote.server.repository.NotebookContent
import polynote.server.repository.format.NotebookFormat
import zio.{RIO, ZIO}

class IPythonFormat extends NotebookFormat {

  override val extension: String = "ipynb"

  override val mime: String = "application/x-ipynb+json"

  override def decodeNotebook(noExtPath: String, rawContent: String): RIO[Any, Notebook] = for {
    parsed  <- ZIO.fromEither(parse(rawContent))
    staged  <- ZIO.fromEither(parsed.as[JupyterNotebookStaged])
    decoded <- ZIO.fromEither(if (staged.nbformat == 3) parsed.as[JupyterNotebookV3].map(JupyterNotebookV3.toV4) else parsed.as[JupyterNotebook])
  } yield JupyterNotebook.toNotebook(decoded).toNotebook(s"$noExtPath.$extension")

  override def encodeNotebook(nb: NotebookContent): RIO[Any, String] = for {
    ipynb <- ZIO(JupyterNotebook.fromNotebook(nb))
  } yield Printer.spaces2.copy(dropNullValues = true).pretty(ipynb.asJson)
}

