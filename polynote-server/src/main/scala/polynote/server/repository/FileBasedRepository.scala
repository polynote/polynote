package polynote.server.repository

import java.io.{FileNotFoundException, OutputStream}
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.file.{FileAlreadyExistsException, Path, Paths}
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import polynote.kernel.NotebookRef.AlreadyClosed
import polynote.kernel.{BaseEnv, GlobalEnv, NotebookRef, Result}
import polynote.kernel.environment.Config
import polynote.kernel.logging.Logging
import polynote.kernel.util.LongRef
import polynote.messages._
import polynote.server.repository.format.NotebookFormat
import polynote.server.repository.fs.{LocalFilesystem, NotebookFilesystem, WAL}, WAL.WALWriter
import scodec.Codec
import zio.{Fiber, IO, Promise, Queue, RIO, Ref, Schedule, Semaphore, Task, UIO, URIO, ZIO}
import zio.ZIO.effectTotal
import zio.blocking.effectBlocking
import zio.clock.currentDateTime
import zio.duration.Duration
import zio.stream.Take
import zio.interop.catz._



class FileBasedRepository(
  val path: Path,
  val chunkSize: Int = 8192,
  val defaultExtension: String = "ipynb",
  val fs: NotebookFilesystem = new LocalFilesystem() // TODO: once we support other FS (like S3) we'll want this to be configurable
) extends NotebookRepository {
  protected def pathOf(relativePath: String): Path = path.resolve(relativePath)

  private final class FileNotebookRef private (
    current: Ref[(Int, Notebook)],
    pending: Queue[Take[Nothing, (NotebookUpdate, Option[Promise[Nothing, (Int, Notebook)]])]],
    closed: Promise[Throwable, Unit],
    log: Logging.Service,
    renameLock: Semaphore,
    process: Promise[Nothing, Fiber[Nothing, Unit]],
    wal: Task[WALWriter],
    saveIntervalMillis: Long = 5000L,
    maxSaveFails: Int = 12
  ) extends NotebookRef {

    // TODO: improve WAL

    private val needSave = new AtomicBoolean(false)
    private val saveNeeded = effectTotal(needSave.get())
    private val setNeedSave = effectTotal(needSave.lazySet(true))
    private val saveIntervalDuration = Duration(saveIntervalMillis, TimeUnit.MILLISECONDS)


    private val consecutiveSaveFails = LongRef.zeroSync
    private val saveSucceeded = effectTotal(needSave.lazySet(false)) *> effectTotal(consecutiveSaveFails.set(0L))
    private val save = (renameLock.withPermit(get.flatMap(saveNotebook)) *> saveSucceeded)
      .catchAll {
        err => consecutiveSaveFails.incrementAndGet.flatMap {
          case count if count >= maxSaveFails =>
            log.error(s"Failed to save notebook ${maxSaveFails} times; closing with error") *>
              closed.fail(err) *> pending.offer(Take.End).unit
          case count =>
            log.error(Some(s"Failed to save notebook $count times (will retry)"), err)
        }
      }

    private def ifOpen[R, A](zio: URIO[R, A]): ZIO[R, AlreadyClosed, A] =
      closed.isDone.flatMap {
        case false => zio
        case true  => NotebookRef.alreadyClosed
      }

    override def getVersioned: UIO[(Int, Notebook)] = current.get

    override def update(update: NotebookUpdate): IO[AlreadyClosed, Unit] =
      ifOpen(pending.offer(Take.Value((update, None)))).unit

    override def updateAndGet(update: NotebookUpdate): IO[AlreadyClosed, (Int, Notebook)] = ifOpen {
      for {
        completer <- Promise.make[Nothing, (Int, Notebook)]
        _         <- pending.offer(Take.Value((update, Some(completer))))
        result    <- completer.await
      } yield result
    }

    // TODO: This bypasses the update processing, which might be unsafe because an update that hasn't been processed yet
    //       result in the given cell being introduced. In practice, since results originate from running a cell, that
    //       cell must already exist. Even so, it would be better for cell results to be NotebookUpdates, so that they
    //       could participate properly in the serial history. We just probably don't want to have to deal with the
    //       global/local versions for them, which is currently a requirement with NotebookUpdates. That could be
    //       refactored a bit.
    override def addResult(cellID: CellID, result: Result): IO[AlreadyClosed, Unit] = ifOpen {
      effectTotal(result.toCellUpdate).flatMap {
        cellUpdate => current.update {
          case (ver, notebook) => ver -> notebook.updateCell(cellID)(cellUpdate)
        }
      }
    } <* setNeedSave

    override def clearResults(cellID: CellID): IO[AlreadyClosed, Unit] = ifOpen {
      current.update {
        case (ver, notebook) => ver -> notebook.updateCell(cellID)(_.copy(results = ShortList.Nil))
      }
    } <* setNeedSave

    override def clearAllResults(): IO[AlreadyClosed, List[CellID]] = ifOpen {
      current.modify {
        case (ver, notebook) =>
          val (clearedIds, updatedCells) = notebook.cells.foldRight((List.empty[CellID], List.empty[NotebookCell])) {
            case (cell, (clearedIds, updatedCells)) =>
              if (cell.results.nonEmpty)
                (cell.id :: clearedIds) -> (cell.copy(results = ShortList(Nil)) :: updatedCells)
              else
                clearedIds -> (cell :: updatedCells)
          }
          clearedIds -> (ver -> notebook.copy(cells = ShortList(updatedCells)))
      }
    } <* setNeedSave

    override def rename(newPath: String): RIO[BaseEnv with GlobalEnv, String] = renameLock.withPermit {
      for {
        currentPath <- path
          newPath     <- renameNotebook(currentPath, newPath)
          _           <- current.update {
            case (ver, notebook) => ver -> notebook.copy(path = ShortString(newPath))
          }
      } yield newPath
    } <* setNeedSave

    override def close(): Task[Unit] =
      closed.succeed(()) *>
        pending.offer(Take.End) *>
        pending.awaitShutdown *>
        process.await.flatMap(_.join) *>
        closed.await

    override val isOpen: UIO[Boolean] = closed.isDone.map(!_)

    override def awaitClosed: Task[Unit] = closed.await.ensuring(process.await.flatMap(_.await.unit))

    private val verify = get.flatMap {
      notebook =>
        loadNotebook(notebook.path).flatMap {
          loaded =>
            val verified = loaded.cells.zip(notebook.cells).forall {
              case (a, b) => a.content.toString == b.content.toString
            }
            if (verified) ZIO.unit else ZIO.fail(new IllegalStateException("Persisted notebook doesn't match in-memory notebook"))
        }.catchAll {
          err => encodeNotebook(notebook).flatMap {
            encoded =>
              Logging.error(
                s"""Notebook verification failed for ${notebook.path} – were there filesystem errors during reading or writing?
                   |Dumping to console:
                   |$encoded
                   |""".stripMargin,
                err
              )
          }.orDie
        }
    }

    private def init(): RIO[BaseEnv with GlobalEnv, Unit] = {
      def doUpdate(update: NotebookUpdate) = current.updateAndGet {
        case (prevVer, notebook) =>
          val nextVer = prevVer + 1
          val next = try {
            // TODO: Notebook updates should be total, but they're currently not because InsertCell is assigned
            //       a CellID by the client. It's possible two clients try to insert the same cell simultaneously.
            //       Instead, the CellID should be assigned by the server, and then that case will become impossible.
            //       For now, it's just very unlikely. We can't emit an error here (inside of ref update) so instead of
            //       resorting to STM we'll just log an error in case this unlikely event happens.
            update.applyTo(notebook)
          } catch {
            case err: Throwable =>
              log.errorSync(Some("Notebook update was dropped because it failed to apply"), err)
              notebook
          }
          nextVer -> next
      } <* setNeedSave

      def notifyCompleter(completerOpt: Option[Promise[Nothing, (Int, Notebook)]]): ((Int, Notebook)) => UIO[Unit] =
        tup => completerOpt match {
          case Some(completer) => completer.succeed(tup).unit
          case None            => ZIO.unit
        }

      def writeWAL(update: NotebookUpdate): RIO[BaseEnv, Unit] = wal.flatMap(_.appendMessage(update)).catchAll {
        err => Logging.error("Unable to write update to WAL", err) // TODO: improve this
      }.uninterruptible

      val syncWAL = wal.flatMap(_.sync()).catchAll {
        err => Logging.error("Unable to sync WAL; changes won't be recoverable until sync succeeds", err)
      }

      val closeWAL = wal.flatMap(_.close()).uninterruptible.catchAll {
        err => Logging.error("Failed to close WAL", err)
      }

      // process all the submitted updates into the ref in order
      val processPending = pending.take.flatMap {
        case Take.Value((update, completerOpt)) => (writeWAL(update).forkDaemon &> doUpdate(update) >>= notifyCompleter(completerOpt)).asSomeError
        case Take.End                           => pending.shutdown *> closed.succeed(()).unit <* ZIO.fail(None)
        case Take.Fail(_)                       => ZIO.dieMessage("Unreachable state")  // Scala 2.11 exhaustivity check doesn't realize
      }.forever.flip

      val doSave = syncWAL &> save.whenM(saveNeeded)

      // every interval, check if the notebook needs to be written and do so
      val saveAtInterval =
        get.map(_.path).flatMap(path => Logging.info(s"Will write to $path")) *>
          doSave.repeat(Schedule.fixed(saveIntervalDuration))

      // save one more time once closed
      val finalSave = doSave *> get.map(_.path).flatMap(path => Logging.info(s"Stopped writing to $path")) <* verify

      processPending.unit.raceFirst(saveAtInterval.unit)
        .ensuring(finalSave).ensuring(closeWAL)
        .forkDaemon.flatMap(process.succeed).unit
    }
  }

  private object FileNotebookRef {
    private val timestampFormat = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    private val currentTimeStr = currentDateTime.flatMap(time => ZIO(time.format(timestampFormat)))

    private def openWAL(notebookPath: String): RIO[BaseEnv with GlobalEnv, WALWriter] =
      Config.access.map(_.storage.wal.enable).flatMap {
        case true =>
          currentTimeStr.flatMap {
            timeStr => fs.createLog(path.resolve(".wal").resolve(s"$notebookPath.$timeStr.wal"))
          }
        case false => ZIO.succeed(WALWriter.NoWAL)
      }

    def apply(notebook: Notebook, version: Int): RIO[BaseEnv with GlobalEnv, FileNotebookRef] = for {
      log        <- Logging.access
      current    <- Ref.make(version -> notebook)
      closed     <- Promise.make[Throwable, Unit]
      pending    <- Queue.unbounded[Take[Nothing, (NotebookUpdate, Option[Promise[Nothing, (Int, Notebook)]])]]
      renameLock <- Semaphore.make(1L)
      process    <- Promise.make[Nothing, Fiber[Nothing, Unit]]
      env        <- ZIO.environment[BaseEnv with GlobalEnv]
      wal        <- openWAL(notebook.path.stripPrefix("/")).tap(_.writeHeader(notebook)).provide(env).memoize
      ref         = new FileNotebookRef(current, pending, closed, log, renameLock, process, wal)
      _          <- ref.init().onError(_ => ref.close().orDie)
    } yield ref
  }

  override def loadNotebook(path: String): RIO[BaseEnv with GlobalEnv, Notebook] = for {
    fmt            <- NotebookFormat.getFormat(pathOf(path))
    content        <- fs.readPathAsString(pathOf(path))
    (noExtPath, _)  = extractExtension(path)
    nb             <- fmt.decodeNotebook(noExtPath, content)
  } yield nb

  override def openNotebook(path: String): RIO[BaseEnv with GlobalEnv, NotebookRef] = for {
    nb             <- loadNotebook(path)
    ref            <- FileNotebookRef(nb, 0)
  } yield ref

  private def encodeNotebook(nb: Notebook) = for {
    fmt       <- NotebookFormat.getFormat(Paths.get(nb.path))
    rawString <- fmt.encodeNotebook(NotebookContent(nb.cells, nb.config))
  } yield rawString

  // TODO: should make this protected so you must save via NotebookRef? And createNotebook would return a NotebookRef.
  override def saveNotebook(nb: Notebook): RIO[BaseEnv with GlobalEnv, Unit] = for {
    rawString <- encodeNotebook(nb).filterOrFail(_.nonEmpty)(new IllegalStateException("Encoded notebook is empty!"))
    _         <- fs.writeStringToPath(pathOf(nb.path), rawString)
  } yield ()

  override def listNotebooks(): RIO[BaseEnv with GlobalEnv, List[String]] = {
    for {
      files <- fs.list(path)
      isSupported <- NotebookFormat.isSupported
    } yield files.filter(isSupported).map { relativePath =>
      path.relativize(relativePath).toString
    }
  }

  override def notebookExists(path: String): RIO[BaseEnv with GlobalEnv, Boolean] = fs.exists(pathOf(path))

  override def notebookURI(path: String): RIO[BaseEnv with GlobalEnv, Option[URI]] = {
    val repoPath = this.path.resolve(path)
    notebookExists(path).map {
      exists =>
        if (exists) {
          Option(repoPath.toAbsolutePath.toUri)
        } else {
          None
        }
    }
  }

  val EndsWithNum = """^(.*?)(\d+)$""".r

  def findUniqueName(path: String): RIO[BaseEnv with GlobalEnv, String] = {
    val (noExtPath, ext) = extractExtension(path)
    notebookExists(path).flatMap {
      case true =>
        noExtPath match {
          case EndsWithNum(base, num) =>
            findUniqueName(s"$base${num.toInt + 1}.$ext")
          case _ =>
            findUniqueName(s"${noExtPath}2.$ext") // start at two because the first one is implicitly #1? Or is that weird?
        }
      case false =>
        ZIO.succeed(path)
    }
  }

  def emptyNotebook(path: String, title: String): RIO[BaseEnv with GlobalEnv, Notebook] = Config.access.map {
    config =>
      Notebook(
        path,
        ShortList.of(NotebookCell(0, "text", s"# $title\n\nThis is a text cell. Start editing!")),
        Some(NotebookConfig.fromPolynoteConfig(config)))
  }

  private def extractExtension(path: String) = {
    path.lastIndexOf('.') match {
      case -1 => (path, defaultExtension)  // Adds the default extension if none is found.
      case idx =>
        (path.substring(0, idx), path.substring(idx + 1))
    }
  }

  override def createNotebook(relativePath: String, maybeContent: Option[String] = None): RIO[BaseEnv with GlobalEnv, String] = {
    val (noExtPath, ext) = extractExtension(relativePath.replaceFirst("""^/+""", ""))
    val path = s"$noExtPath.$ext"

    for {
      _       <- fs.validate(Paths.get(relativePath))
      fmt     <- NotebookFormat.getFormat(Paths.get(path))
      nb      <- maybeContent.map(content => fmt.decodeNotebook(noExtPath, content)).getOrElse {
        val defaultTitle = noExtPath.split('/').last.replaceAll("[\\s\\-_]+", " ").trim()
        emptyNotebook(path, defaultTitle)
      }
      name    <- findUniqueName(nb.path)
      _       <- saveNotebook(nb.copy(path = name))
    } yield name
  }

  override def createAndOpen(path: String, notebook: Notebook, version: Int): RIO[BaseEnv with GlobalEnv, NotebookRef] = for {
    _   <- saveNotebook(notebook.copy(path = path))
    ref <- FileNotebookRef(notebook, version)
  } yield ref

  private def withValidatedSrcDest[T](src: String, dest: String)(f: (String, String) => RIO[BaseEnv with GlobalEnv, T]): RIO[BaseEnv with GlobalEnv, T] = {
    val (destNoExt, destExt) = extractExtension(dest.replaceFirst("""^/+""", ""))
    val withExt = s"$destNoExt.$destExt"

    for {
      _   <- fs.validate(Paths.get(withExt))
      _   <- notebookExists(src).filterOrFail(identity)(new FileNotFoundException(s"File $src doesn't exist"))
      _   <- notebookExists(withExt).filterOrFail(!_)(new FileAlreadyExistsException(s"File $withExt already exists"))
      res <- f(src, withExt)
    } yield res
  }

  override def renameNotebook(path: String, newPath: String): RIO[BaseEnv with GlobalEnv, String] = withValidatedSrcDest(path, newPath) {
    (src, dest) =>
      fs.move(pathOf(src), pathOf(dest)).as(dest)
  }

  override def copyNotebook(path: String, newPath: String): RIO[BaseEnv with GlobalEnv, String] = withValidatedSrcDest(path, newPath) {
    (src, dest) =>
      fs.copy(pathOf(src), pathOf(dest)).as(dest)
  }

  // TODO: should probably have a "trash" or something instead – a way of recovering a file from accidental deletion?
  override def deleteNotebook(path: String): RIO[BaseEnv with GlobalEnv, Unit] = fs.delete(pathOf(path))

  override def initStorage(): RIO[BaseEnv with GlobalEnv, Unit] = fs.init(path)
}
