package polynote.server.repository
package ipynb

import java.nio.file.Path

import cats.effect.{ConcurrentEffect, ContextShift, IO}
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import io.circe.parser.parse
import io.circe.Printer
import io.circe.syntax._
import polynote.config.PolynoteConfig
import polynote.messages.Notebook

import scala.concurrent.ExecutionContext

class IPythonNotebookRepository[F[_]](
  val path: Path,
  val config: PolynoteConfig,
  saveVersion: Int = 4,
  val chunkSize: Int = 8192,
  val executionContext: ExecutionContext = ExecutionContext.global)(implicit
  F: ConcurrentEffect[F],
  contextShift: ContextShift[F]
) extends FileBasedRepository[F] {

  override protected val defaultExtension: String = "ipynb"

  def loadNotebook(path: String): F[Notebook] = for {
    str     <- loadString(path)
    parsed  <- F.fromEither(parse(str))
    staged  <- F.fromEither(parsed.as[JupyterNotebookStaged])
    decoded <- F.fromEither(if (staged.nbformat == 3) parsed.as[JupyterNotebookV3].map(JupyterNotebookV3.toV4) else parsed.as[JupyterNotebook])
  } yield JupyterNotebook.toNotebook(path, decoded)

  def saveNotebook(path: String, cells: Notebook): F[Unit] = for {
    ipynb <- F.delay(JupyterNotebook.fromNotebook(cells))
    json   = if (saveVersion == 3) JupyterNotebookV3.fromV4(ipynb).asJson else ipynb.asJson
    str    = Printer.spaces2.copy(dropNullValues = true).pretty(json)
    _     <- writeString(path, str)
  } yield ()
}

