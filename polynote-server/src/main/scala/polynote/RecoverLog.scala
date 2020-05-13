package polynote

import java.nio.file.{Files, Paths, StandardOpenOption}

import polynote.app.MainArgs
import polynote.kernel.logging.Logging
import polynote.messages.{Message, Notebook, NotebookUpdate, ShortList}
import polynote.server.AppEnv
import zio.{Ref, Runtime, Task, UIO, ZIO}
import zio.ZIO.effectTotal
import zio.blocking.effectBlocking
import zio.interop.catz._
import fs2.Stream
import polynote.server.repository.NotebookContent
import polynote.server.repository.format.ipynb.IPythonFormat

object RecoverLog {

  def replay(messages: Stream[Task, Message], ref: Ref[Notebook], log: Logging.Service): UIO[Unit] = messages.evalMap {
    case nb: Notebook => ref.set(nb)
    case upd: NotebookUpdate => ref.update {
      nb => try {
        upd.applyTo(nb)
      } catch {
        case err: Throwable =>
          log.errorSync(Some("Dropped update because an error occurred when applying it"), err)
          nb
      }
    }
    case _ => ZIO.unit
  }.compile.drain.catchAll {
    err =>
      log.error(Some("Error occurred while replaying the log; printing the final state anyway."), err)
  }

  def main(implicit runtime: Runtime[Any]): ZIO[AppEnv, String, Int] = for {
    args     <- ZIO.access[MainArgs](_.get.rest)
    path     <- ZIO(args.head).flatMap(pathStr => effectBlocking(Paths.get(pathStr).toRealPath())).orDie
    is       <- effectBlocking(Files.newInputStream(path, StandardOpenOption.READ)).orDie
    log      <- Logging.access
    _        <- Logging.info(s"Reading log entries from ${path}...")
    messages  = scodec.stream.decode.many[Message](Message.codec).decodeInputStream[Task](is)
    ref      <- Ref.make(Notebook("", ShortList.Nil, None))
    _        <- replay(messages, ref, log)
    format    = new IPythonFormat
    result   <- ref.get
    encoded  <- format.encodeNotebook(NotebookContent(result.cells, result.config)).orDie
    _        <- effectTotal(println(encoded))
  } yield 0
}
