package polynote

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.time.{Instant, LocalDate, OffsetDateTime, ZoneId, ZoneOffset}
import java.time.format.{DateTimeFormatter, DateTimeFormatterBuilder}

import polynote.app.MainArgs
import polynote.kernel.{BaseEnv, CellEnv, GlobalEnv, Kernel, KernelStatusUpdate}
import polynote.kernel.environment.{CurrentNotebook, NotebookUpdates, PublishResult, PublishStatus}
import polynote.kernel.task.TaskManager
import polynote.kernel.util.Publish
import polynote.messages.{CellID, Notebook, NotebookCell}
import polynote.server.AppEnv
import polynote.server.repository.{NotebookContent, NotebookRepository}
import polynote.server.repository.format.NotebookFormat
import polynote.server.repository.fs.FileSystems
import zio.blocking.{Blocking, effectBlocking}
import zio.{Has, IO, Queue, RIO, Ref, Task, UIO, URIO, URLayer, ZIO, ZLayer}
import ZIO.{effect, effectTotal}
import zio.clock, clock.Clock
import zio.interop.catz._
import zio.stream.Take

import scala.annotation.tailrec

object NotebookRunner {

  type CellBaseEnv = BaseEnv with GlobalEnv with CurrentNotebook with TaskManager with PublishStatus
  type KernelFactoryEnv = CellBaseEnv with NotebookUpdates

  def main: ZIO[AppEnv, String, Int] = for {
    mainArgs      <- MainArgs.access
    runnerArgs    <- Args.parseOrErr(mainArgs.rest)
    statusUpdates <- Queue.unbounded[Take[Nothing, KernelStatusUpdate]]
    publishers     = PublishStatus.layer(statusUpdates)
    results       <- ZIO.foreach(runnerArgs.inputFiles)(path => runPath(runnerArgs, path))
      .provideSomeLayer[AppEnv](publishers)
      .orDie
  } yield 0


  def runPath(runnerArgs: Args, pathStr: String): RIO[AppEnv with PublishStatus, Unit] = for {
    path   <- parsePath(pathStr)
    result <- loadNotebook(path) >>= runNotebook
    _      <- writeResult(runnerArgs, path, result)
  } yield ()

  def parsePath(path: String): RIO[Blocking, Path] = effect(Paths.get(path)).flatMap {
    case path if path.startsWith(".") => effectBlocking(path.toAbsolutePath)
    case path => ZIO.succeed(path)
  }

  def loadNotebook(path: Path): RIO[AppEnv, Notebook] = path match {
    case path if path.isAbsolute =>
      for {
        format   <- NotebookFormat.getFormat(path)
        content  <- FileSystems.readPathAsString(path)
        notebook <- format.decodeNotebook(path.toString.stripSuffix(s".${format.extension}"), content)
      } yield notebook

    case path => NotebookRepository.loadNotebook(path.toString)
  }

  def runNotebook(notebook: Notebook): RIO[AppEnv with PublishStatus, Notebook] =
    runWithEnv.provideSomeLayer[AppEnv with PublishStatus] {
      CurrentNotebook.const(notebook) ++ TaskManager.layer ++ NotebookUpdates.empty
    }

  def runWithEnv: ZIO[AppEnv with CellBaseEnv with NotebookUpdates, Throwable, Notebook] =
    startKernel.provideSomeLayer[KernelFactoryEnv](PublishResult.ignore).toManaged(_.shutdown().orDie).use {
      kernel =>
        for {
          notebook <- CurrentNotebook.get
          cells     = notebook.cells.filter(shouldRunCell).map(_.id)
          // make a new ref for the updated notebook (existing one is not modified)
          nbRef    <- Ref.make(notebook)
          _        <- ZIO.foreach(cells)(cell => runCell(cell, nbRef, kernel))
          result   <- nbRef.get
      } yield result
  }

  def runCell(id: CellID, nbRef: Ref[Notebook], kernel: Kernel): RIO[CellBaseEnv, Unit] =
    kernel.queueCell(id).flatten.provideSomeLayer[CellBaseEnv] {
      // when results are published, update the notebook with them
      PublishResult.layer(Publish.fn(result => nbRef.update(_.updateCell(id)(result.toCellUpdate))))
    }

  def startKernel: ZIO[BaseEnv with GlobalEnv with CellEnv with NotebookUpdates, Throwable, Kernel] = for {
    kernel <- Kernel.Factory.newKernel
    _      <- kernel.init()
  } yield kernel

  private def shouldRunCell(cell: NotebookCell) = cell.language.toString match {
    case "text" | "vega" | "markdown" => false
    case _ => true
  }

  def writeResult(runnerArgs: Args, sourcePath: Path, notebook: Notebook): RIO[AppEnv, Unit] =
    clock.currentDateTime.flatMap {
      dateTime =>
        runnerArgs.targetFile match {
          case None => ZIO.unit
          case Some(modPath) =>
            modPath(dateTime.toInstant)(sourcePath) match {
              case path if path.isAbsolute =>
                for {
                  format  <- NotebookFormat.getFormat(path)
                  content <- format.encodeNotebook(NotebookContent(notebook.cells, notebook.config))
                  _       <- FileSystems.writeStringToPath(path, content)
                } yield ()

              case path => NotebookRepository.saveNotebook(notebook.copy(path = path.toString))
            }
        }
    }


  def usage: String =
    """Usage:
      |
      |polynote.py run [OPTIONS] FILE
      |
      |Input file(s) are any number of:
      |  - Absolute paths (with leading / or .) to notebook files on the local filesystem
      |  - Relative paths (without leading / or .) to notebook files in the configured notebook repository
      |    (see storage options in config.yml)
      |No glob expansion is performed, because the paths may be absolute local paths or repository-based paths. Instead,
      |use xargs (and your shell) to perform glob expansion for local files.
      |
      |Options:
      |  -c, --config                Use the specified config-file to configure Polynote (default: config.yml).
      |  --overwrite                 Overwrite the input notebook file(s) with the result after running.
      |  -o, --output                Specify how to output the resulting notebook files. See "Output specifiers".
      |
      |If --overwrite is given, the original input files will be overwritten. If -o or --output is specified, the next
      |argument(s) should be an output specifier which describes how to compute the path to which the result is written.
      |If a notebook file already exists at the computed path, it will be overwritten.
      |
      |If neither argument is specified, no output is written.
      |
      |Output specifiers:
      |  The argument to -o (or --output) specifies to which file (per input file) the resulting notebook outputs
      |  will be written. The following options are available:
      |
      |    append-datestamp          Append a datestamp to the path (before file extension), and then write the result
      |                              to that path alongside the original. The datestamp is of the form -YYYYmmdd. For
      |                              example, if the input path is `foo/bar/baz.ipynb` (which is a relative path, so is
      |                              from the configured repository) and the local system date is June 12, 2020, the
      |                              result notebook is written to `foo/bar/baz-20200612.ipynb` in the configured
      |                              repository.
      |
      |    append-timestamp          Append a UTC timestamp to the path, in a similar fashion to append-datestamp,
      |                              except a UTC timestamp of the form -YYYYmmddhhiiss is appended to the input path.
      |
      |    append-const              Append a constant string (given as an additional argument) to the filename. Unlike
      |                              append-datestamp and append-timestamp, no `-` delimiter is included unless
      |                              specified in the string. For example, if the command is:
      |                                 run -o append-const flibbert foo/bar/baz.ipynb
      |                              then the output path is computed to be:
      |                                 foo/bar/bazflibbert.ipynb
      |""".stripMargin

  case class Args(
    targetFile: Option[Instant => Path => Path] = None,
    inputFiles: List[String] = Nil
  )

  object Args {
    @tailrec
    def parse(args: List[String], current: Args = Args()): Either[String, Args] = args match {
      case sw@("-o" | "--output") :: rest => rest match {
        case "append-datestamp" :: rest => parse(rest, current.copy(targetFile = Some(appendDatestamp)))
        case "append-timestamp" :: rest => parse(rest, current.copy(targetFile = Some(appendTimestamp)))
        case "append-const" :: str :: rest => parse(rest, current.copy(targetFile = Some(_ => append(str))))
        case other :: rest => Left(s"$sw: Invalid output specifier $other")
        case Nil => Left(s"$sw: No output specifier or input files given")
      }
      case "--overwrite" :: rest => parse(rest, current.copy(targetFile = Some(_ => identity[Path])))
      case flag :: _ if flag startsWith ("-") => Left(s"Unknown option $flag")
      case nb :: rest => Right(current.copy(inputFiles = current.inputFiles ++ (nb :: rest)))
      case Nil => Right(current)
    }

    def parseOrErr(args: List[String]): ZIO[Clock, String, Args] = ZIO.fromEither(parse(args))
        .filterOrFail(_.inputFiles.nonEmpty)("No input files specified")
        .mapError(errStr => s"$usage\n\nError:$errStr")
  }

  import java.time.temporal.ChronoField.{YEAR, MONTH_OF_YEAR, DAY_OF_MONTH, HOUR_OF_DAY, MINUTE_OF_HOUR, SECOND_OF_MINUTE}

  def datestampBuilder: DateTimeFormatterBuilder = new DateTimeFormatterBuilder()
    .appendValue(YEAR)
    .appendValue(MONTH_OF_YEAR, 2)
    .appendValue(DAY_OF_MONTH, 2)

  def timestampBuilder: DateTimeFormatterBuilder =
    datestampBuilder
      .appendValue(HOUR_OF_DAY, 2)
      .appendValue(MINUTE_OF_HOUR, 2)
      .appendValue(SECOND_OF_MINUTE, 2)

  private def append(value: String): Path => Path = path => splitExt(path) match {
    case (name, extOpt) =>
      val ext = extOpt.map(ext => s".$ext").getOrElse("")
      Paths.get(s"$name$value$ext")
  }

  private def appendDatestamp: Instant => Path => Path =
    instant => append("-" + instant.atZone(ZoneId.systemDefault()).format(datestampBuilder.toFormatter))

  private def appendTimestamp: Instant => Path => Path = instant => append(
    "-" + (instant.atZone(ZoneOffset.UTC)).format(timestampBuilder.toFormatter))

  private def splitExt(path: Path): (String, Option[String]) = {
    val str = path.toString
    str.lastIndexOf('.') match {
      case -1 => str -> None
      case n  => str.splitAt(n) match {
        case (before, after) => before -> Some(after.stripPrefix("."))
      }
    }
  }

}
