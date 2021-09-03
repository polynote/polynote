package polynote

import java.nio.channels.FileChannel
import java.nio.file.{Paths, StandardOpenOption}
import java.time.Instant
import polynote.app.{Args, MainArgs}
import polynote.kernel.logging.Logging
import polynote.messages.{Message, Notebook, NotebookUpdate, ShortList}
import polynote.server.AppEnv
import zio.{ERefM, Ref, Task, URIO, ZIO, ZManaged, ZRefM}
import zio.ZIO.effectTotal
import zio.blocking.effectBlocking
import polynote.server.repository.NotebookContent
import polynote.server.repository.format.ipynb.IPythonFormat
import polynote.server.repository.fs.WAL
import zio.stream.ZStream

object RecoverLog {

  def replay(messages: ZStream[AppEnv, Throwable, (Instant, Message)], ref: ERefM[Throwable, Notebook]): URIO[AppEnv, Unit] = {
    messages.map(_._2).mapM {
      case nb: Notebook => ref.set(nb)
      case upd: NotebookUpdate => ref.update {
        nb => ZIO(upd.applyTo(nb)).onError(Logging.error("Dropped update because an error occurred when applying it", _))
      }
      case _ => ZIO.unit
    }.runDrain.catchAll(Logging.error("Error occurred while replaying the log; printing the final state anyway.", _))
  }

  def main: ZIO[AppEnv, String, Int] = for {
    args     <- ZIO.access[MainArgs](_.get[Args].rest)
    path     <- ZIO(args.head).flatMap(pathStr => effectBlocking(Paths.get(pathStr).toRealPath())).orDie
    is       =  ZManaged.fromAutoCloseable(effectBlocking(FileChannel.open(path, StandardOpenOption.READ))).orDie
    log      <- Logging.access
    _        <- Logging.info(s"Reading log entries from ${path}...")
    messages  = WAL.decode(is)
    ref      <- ZRefM.make(Notebook("", ShortList.Nil, None))
    _        <- replay(messages, ref)
    format    = new IPythonFormat
    result   <- ref.get
    encoded  <- format.encodeNotebook(NotebookContent(result.cells, result.config)).orDie
    _        <- effectTotal(println(encoded))
  } yield 0
}
