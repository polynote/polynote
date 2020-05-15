package polynote

import java.nio.channels.FileChannel
import java.nio.file.{Files, Paths, StandardOpenOption}
import java.time.Instant

import cats.effect.Effect
import polynote.app.{Args, MainArgs}
import polynote.kernel.logging.Logging
import polynote.messages.{Message, Notebook, NotebookUpdate, ShortList}
import polynote.server.AppEnv
import zio.{Ref, Runtime, Task, UIO, ZIO}
import zio.ZIO.effectTotal
import zio.blocking.effectBlocking
import fs2.Stream
import polynote.server.repository.{FileBasedRepository, NotebookContent}
import polynote.server.repository.format.ipynb.IPythonFormat
import polynote.server.repository.fs.WAL
import polynote.server.taskConcurrent
import scodec.bits.ByteVector
import scodec.stream.decode
import scodec.codecs
import scodec.stream.decode.StreamDecoder

object RecoverLog {

  def replay(messages: Stream[Task, (Instant, Message)], ref: Ref[Notebook], log: Logging.Service): UIO[Unit] = messages.map(_._2).evalMap {
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

  def main(implicit ev: Effect[Task]): ZIO[AppEnv, String, Int] = for {
    args     <- ZIO.access[MainArgs](_.get[Args].rest)
    path     <- ZIO(args.head).flatMap(pathStr => effectBlocking(Paths.get(pathStr).toRealPath())).orDie
    is       <- effectBlocking(FileChannel.open(path, StandardOpenOption.READ)).orDie
    log      <- Logging.access
    _        <- Logging.info(s"Reading log entries from ${path}...")
    messages  = WAL.decoder.decodeMmap(is)
    ref      <- Ref.make(Notebook("", ShortList.Nil, None))
    _        <- replay(messages, ref, log)
    format    = new IPythonFormat
    result   <- ref.get
    encoded  <- format.encodeNotebook(NotebookContent(result.cells, result.config)).orDie
    _        <- effectTotal(println(encoded))
  } yield 0
}
