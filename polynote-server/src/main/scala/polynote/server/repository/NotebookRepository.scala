package polynote.server.repository

import java.io.{File, FileNotFoundException, IOException}
import java.net.URI
import java.nio.file.{FileAlreadyExistsException, Path, Paths}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}

import cats.implicits._
import polynote.config.{Mount, PolynoteConfig}
import polynote.kernel.NotebookRef.AlreadyClosed
import polynote.kernel.environment.Config
import polynote.kernel.logging.Logging
import polynote.kernel.util.LongRef
import polynote.kernel.{BaseEnv, GlobalEnv, NotebookRef, Result}
import polynote.messages._
import polynote.server.repository.format.NotebookFormat
import polynote.server.repository.fs.{FileSystems, LocalFilesystem, NotebookFilesystem}
import zio.clock.Clock
import zio.duration.Duration
import zio.interop.catz._
import zio.stream.Take
import zio.{Fiber, Has, IO, Promise, Queue, RIO, Ref, Schedule, Semaphore, Task, UIO, URIO, URLayer, ZIO, ZLayer}
import ZIO.{effect, effectTotal}

/**
  * A Notebook Repository operates on notebooks stored by path. The path of a notebook should be a forward-slash
  * separated path with no leading slash. Such paths must be passed wherever paths are expected and returned wherever
  * paths are returned (including in a [[Notebook]]'s `path` field).
  */
trait NotebookRepository {

  /**
    * @return Whether a notebook exists at the specified path
    */
  def notebookExists(path: String): RIO[BaseEnv with GlobalEnv, Boolean]

  /**
    * @return The location of the notebook (must be absolute)
    */
  def notebookURI(path: String): RIO[BaseEnv with GlobalEnv, Option[URI]]

  /**
    * @return The notebook at the specified path
    */
  def loadNotebook(path: String): RIO[BaseEnv with GlobalEnv, Notebook]

  /**
    * Save the given notebook to the specified path
    */
  def saveNotebook(nb: Notebook): RIO[BaseEnv with GlobalEnv, Unit]


  def openNotebook(path: String): RIO[BaseEnv with GlobalEnv, NotebookRef]

  /**
    * @return A list of notebook paths that exist in this repository
    */
  def listNotebooks(): RIO[BaseEnv with GlobalEnv, List[String]]

  /**
    * Create a notebook at the given path, optionally creating it from the given content string.
    *
    * TODO: Server no longer imports. Need to implement this on the client side.
    */
  def createNotebook(path: String, maybeContent: Option[String]): RIO[BaseEnv with GlobalEnv, String]
  def createAndOpen(path: String, notebook: Notebook, version: Int = 0): RIO[BaseEnv with GlobalEnv, NotebookRef]

  def renameNotebook(path: String, newPath: String): RIO[BaseEnv with GlobalEnv, String]

  def copyNotebook(path: String, newPath: String): RIO[BaseEnv with GlobalEnv, String]

  def deleteNotebook(path: String): RIO[BaseEnv with GlobalEnv, Unit]

  /**
    * Initialize the storage for this repository (i.e. create directory if it doesn't exist)
    */
  def initStorage(): RIO[BaseEnv with GlobalEnv, Unit]
}

object NotebookRepository {
  val live: URLayer[Config with FileSystems, Has[NotebookRepository]] = ZLayer.fromServiceM {
    (config: PolynoteConfig) => makeTreeRepository(config.storage.dir, config.storage.mounts, config)
  }

  def makeTreeRepository(dir: String, mounts: Map[String, Mount], config: PolynoteConfig): URIO[FileSystems, TreeRepository] = {
    FileSystems.defaultFilesystem.flatMap {
      fs =>
        ZIO.collectAllPar {
          mounts.toSeq.map {
            case (key, mount) =>
              makeTreeRepository(mount.dir, mount.mounts, config).map(key -> _)
          }
        }.map {
          repos =>
            val rootRepo = new FileBasedRepository(new File(System.getProperty("user.dir")).toPath.resolve(dir), fs = fs)
            new TreeRepository(rootRepo, repos.toMap)
        }

    }
  }

  def access: RIO[Has[NotebookRepository], NotebookRepository] = ZIO.access[Has[NotebookRepository]](_.get)
  def loadNotebook(path: String): RIO[BaseEnv with GlobalEnv with Has[NotebookRepository], Notebook] = access.flatMap(_.loadNotebook(path))
  def saveNotebook(notebook: Notebook): RIO[BaseEnv with GlobalEnv with Has[NotebookRepository], Unit] = access.flatMap(_.saveNotebook(notebook))
}

/**
  * Notebook Repository that delegates to child Repositories that correspond to Storage Mounts [[polynote.config.Mount]].
  *
  * @param root:  The repository for this node in the tree
  * @param repos: Map of mount points -> repositories for children of this node
  */
class TreeRepository (
  root: NotebookRepository,
  repos: Map[String, NotebookRepository]
) extends NotebookRepository {

  private class TreeNotebookRef private (
    currentRef: Ref[TreeNotebookRef.State],
    renameLock: Semaphore,
    closed: Promise[Throwable, Unit]
  ) extends NotebookRef {
    import TreeNotebookRef.State
    import renameLock.withPermit

    private def withRef[R, E, A](fn: NotebookRef => ZIO[R, E, A]): ZIO[R, E, A] = currentRef.get.flatMap(tup => fn(tup.ref))

    override val isOpen: UIO[Boolean] = ZIO.mapParN(closed.isDone.map(!_), currentRef.get.flatMap(_.ref.isOpen))(_ && _)

    private val failIfClosed = isOpen.flatMap {
      case true  => ZIO.unit
      case false => currentRef.get.flatMap {
        state => ZIO.fail(AlreadyClosed(Some(state.fullPath)))
      }
    }

    override def getVersioned: UIO[(Int, Notebook)] = withPermit {
      currentRef.get.flatMap {
        state => state.ref.getVersioned.map {
          case (ver, notebook) => ver -> state.normalizePath(notebook)
        }
      }
    }

    override def update(update: NotebookUpdate): IO[AlreadyClosed, Unit] =
      withPermit(withRef(_.update(update)))

    override def updateAndGet(update: NotebookUpdate): IO[AlreadyClosed, (Int, Notebook)] = withPermit {
      currentRef.get.flatMap {
        state => state.ref.updateAndGet(update).map {
          case (ver, notebook) =>
            ver -> state.normalizePath(notebook)
        }.catchSome {
          case AlreadyClosed(Some(path)) => ZIO.fail(AlreadyClosed(Some(pathOf(path, state.basePath))))
        }
      }
    }

    override def addResult(cellID: CellID, result: Result): IO[AlreadyClosed, Unit] =
      withPermit(withRef(_.addResult(cellID, result)))

    override def clearResults(cellID: CellID): IO[AlreadyClosed, Unit] =
      withPermit(withRef(_.clearResults(cellID)))

    override def clearAllResults(): IO[AlreadyClosed, List[CellID]] =
      withPermit(withRef(_.clearAllResults()))

    override def rename(newPath: String): RIO[BaseEnv with GlobalEnv, String] =
      // The semaphore has Short.MaxValue permits so that the other operations can just take one and avoid blocking
      // each other. This operation takes all of them, to block concurrent renames and any other operations during rename.
      failIfClosed *> renameLock.withPermits(Short.MaxValue) {
        currentRef.get.flatMap {
          state => delegate(newPath) {
            case (repo, newRelativePath, _) if repo eq state.repo  =>
              state.ref.rename(deslash(newRelativePath)).map(pathOf(_, state.basePath))
            case (newRepo, relativePath, newBasePath) =>
              for {
                _        <- state.ref.close()
                nb       <- state.ref.getVersioned
                newRef   <- newRepo.createAndOpen(relativePath, nb._2.copy(path = relativePath), nb._1)
                validate <- loadNotebook(newPath)
                  .filterOrFail(_.cells.map(_.copy(id = 0)) == nb._2.cells.map(_.copy(id = 0)))(new IOException("Validation error moving notebook across repositories; will not remove from previous location"))
                  .flatMap(_ => state.repo.deleteNotebook(state.relativePath))
                newState <- State(newRepo, newRef, newBasePath, relativePath, closed)
                _        <- currentRef.set(newState)
              } yield pathOf(relativePath, newBasePath)
          }
        }
      }

    override def close(): Task[Unit] = currentRef.get.flatMap(_.ref.close()) <* closed.succeed(())

    override def awaitClosed: Task[Unit] = closed.await

  }

  private object TreeNotebookRef {
    private case class State private (repo: NotebookRepository, ref: NotebookRef, basePath: Option[String], relativePath: String) {
      def normalizePath(notebook: Notebook): Notebook = TreeRepository.this.normalizePath(
        basePath.map(base => notebook.copy(path = Paths.get(base, notebook.path).toString)).getOrElse(notebook)
      )

      // fail the given promise if the upstream ref fails with an error
      private def closeOnError(promise: Promise[Throwable, Unit]): UIO[Unit] = ref.awaitClosed
        .flip
        .flatMap(err => promise.fail(err))
        .ignore.forkDaemon.unit

      lazy val fullPath: String = pathOf(relativePath, basePath)
    }

    private object State {
      def apply(repo: NotebookRepository, ref: NotebookRef, basePath: Option[String], relativePath: String, closed: Promise[Throwable, Unit]): RIO[BaseEnv with GlobalEnv, State] = {
        val state = new State(repo, ref, basePath, relativePath)
        state.closeOnError(closed).as(state)
      }
    }

    def apply(repo: NotebookRepository, underlying: NotebookRef, basePath: Option[String], relativePath: String): RIO[BaseEnv with GlobalEnv, TreeNotebookRef] = for {
      closed     <- Promise.make[Throwable, Unit]
      state      <- State(repo, underlying, basePath, relativePath, closed)
      underlying <- Ref.make(state)
      renameLock <- Semaphore.make(Short.MaxValue)
    } yield new TreeNotebookRef(underlying, renameLock, closed)
  }

  /**
    * Helper for picking the proper Repository to use for the given notebook path.
    *
    * @param notebookPath: The path to the notebook
    * @param f:            Function for `(NotebookRepository, relativePath: String, maybeBasePath: Option[String) => RIO[Env, T]`.
    *                      The presence of `maybeBasePath` reflects whether the path has been relativized (in case callers need to de-relativize any results)
    */
  private[repository] def delegate[T](notebookPath: String)(f: (NotebookRepository, String, Option[String]) => RIO[BaseEnv with GlobalEnv, T]): RIO[BaseEnv with GlobalEnv, T] = {
    val (originalPath, basePath) = extractPath(Paths.get(reslash(notebookPath)))

    val (repoForPath, relativePath, maybeBasePath) = basePath.flatMap(base => repos.get(base.toString).map(_ -> base)) match {
      case Some((repo, base)) =>
          (repo, base.relativize(originalPath).toString, Option(base.toString))
      case None =>
        (root, originalPath.toString, None)
    }

    f(repoForPath, relativePath, maybeBasePath)
  }



  private def reslash(str: String): String = "/" + deslash(str)
  private def deslash(str: String): String = str.stripPrefix("/")
  private def normalizePath(notebook: Notebook): Notebook = notebook.copy(path = deslash(notebook.path))
  private def pathOf(relativePath: String, basePath: Option[String]): String = deslash(basePath.map(base => Paths.get(base, relativePath).toString).getOrElse(relativePath))

  /**
    * Relativizes the path and extracts the base path (the top-most path member in this path)
    *
    * @return the path, relativized if necessary, and the basePath
    */
  private def extractPath(path: Path): (Path, Option[Path]) = {
    val forceRelativePath = path.subpath(0, path.getNameCount)  // root doesn't count with subpaths, so this trick forces the path to be relative

    (forceRelativePath, Option(forceRelativePath.getParent).map(_.getName(0)))
  }

  override def notebookExists(originalPath: String): RIO[BaseEnv with GlobalEnv, Boolean] = delegate(originalPath) {
    (repo, relativePath, _) => repo.notebookExists(relativePath)
  }

  // Because notebookURI is absolute path we don't need to modify it here
  override def notebookURI(originalPath: String): RIO[BaseEnv with GlobalEnv, Option[URI]] = delegate(originalPath) {
    (repo, relativePath, _) =>
      repo.notebookURI(relativePath)
  }

  override def loadNotebook(originalPath: String): RIO[BaseEnv with GlobalEnv, Notebook] = delegate(originalPath) {
    (repo, relativePath, base) =>
     for {
       nb <- repo.loadNotebook(relativePath)
     } yield normalizePath(base.map(b => nb.copy(path = Paths.get(b, nb.path).toString)).getOrElse(nb))
  }

  override def openNotebook(path: String): RIO[BaseEnv with GlobalEnv, NotebookRef] = delegate(path) {
    (repo, relativePath, base) => repo.openNotebook(relativePath).flatMap {
      underlying => TreeNotebookRef(repo, underlying, base, relativePath)
    }
  }

  override def saveNotebook(nb: Notebook): RIO[BaseEnv with GlobalEnv, Unit] = delegate(nb.path) {
    (repo, relativePath, _) => repo.saveNotebook(nb.copy(path = relativePath))
  }

  override def listNotebooks(): RIO[BaseEnv with GlobalEnv, List[String]] = {
    for {
      rootNBs <- root.listNotebooks().map(_.map(deslash))
      mountNbs <- repos.map {
        case (base, repo) => repo.listNotebooks().map(_.map(nbPath => deslash(Paths.get(base, nbPath).toString)))
      }.toList.sequence
    } yield rootNBs ++ mountNbs.flatten
  }

  override def createNotebook(originalPath: String, maybeContent: Option[String]): RIO[BaseEnv with GlobalEnv, String] = delegate(originalPath) {
    (repo, relativePath, base) =>
        for {
          nbPath <- repo.createNotebook(relativePath, maybeContent)
        } yield deslash(base.map(b => Paths.get(b, nbPath).toString).getOrElse(nbPath))
  }

  override def createAndOpen(path: String, notebook: Notebook, version: Int): RIO[BaseEnv with GlobalEnv, NotebookRef] = delegate(path) {
    (repo, relativePath, base) =>
      for {
        underlyingRef <- repo.createAndOpen(relativePath, notebook, version)
        ref           <- TreeNotebookRef(repo, underlyingRef, base, relativePath)
      } yield ref
  }

  private def copyOrRename(src: String, dest: String, deletePrevious: Boolean): RIO[BaseEnv with GlobalEnv, String] = {
    val (srcPath, srcBase) = extractPath(Paths.get(src))

    val (destPath, destBase) = extractPath(Paths.get(dest))

    if (srcBase == destBase) {
      for {
        (copied, base) <- delegate(srcPath.toString) {
          (repo, repoRelativePathStr, base) =>
            val relativeDest = base.fold(destPath.toString)(repoBase => Paths.get(repoBase).relativize(destPath).toString)
            if (deletePrevious) {
              repo.renameNotebook(repoRelativePathStr, relativeDest).map(_ -> base)
            } else {
              repo.copyNotebook(repoRelativePathStr, relativeDest).map(_ -> base)
            }
        }
      } yield deslash(base.map(b => Paths.get(b, copied).toString).getOrElse(copied))
    } else {
      // If the two paths are on different filesystems, we can't rely on an atomic move or copy, so we need to fall back
      // to handling the creation and deletion ourselves.
      for {
        srcNb                <- delegate(src)((repo, repoRelativePathStr, base) => repo.loadNotebook(repoRelativePathStr))
        (destRepoBase, dest) <- delegate(dest)((repo, repoRelativePathStr, base) => repo.saveNotebook(srcNb.copy(path=repoRelativePathStr)).map(_ => base -> repoRelativePathStr))
        _                    <- if (deletePrevious) delegate(src)((repo, repoRelativePathStr, _) => repo.deleteNotebook(repoRelativePathStr)) else ZIO.unit
      } yield deslash(destRepoBase.map(base => Paths.get(base, dest).toString).getOrElse(dest))
    }
  }

  override def renameNotebook(src: String, dest: String): RIO[BaseEnv with GlobalEnv, String] = copyOrRename(src, dest, deletePrevious = true)

  override def copyNotebook(src: String, dest: String): RIO[BaseEnv with GlobalEnv, String] = copyOrRename(src, dest, deletePrevious = false)

  override def deleteNotebook(originalPath: String): RIO[BaseEnv with GlobalEnv, Unit] = delegate(originalPath) {
    (repo, relativePath, _) => repo.deleteNotebook(relativePath)
  }

  override def initStorage(): RIO[BaseEnv with GlobalEnv, Unit] = for {
    _ <- root.initStorage()
    _ <- repos.values.map(_.initStorage()).toList.sequence
  } yield ()
}

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
    saveIntervalMillis: Long = 5000L,
    maxSaveFails: Int = 12
  ) extends NotebookRef {

    // TODO: this would be a good place to implement WAL of updates

    private val needSave = new AtomicBoolean(false)
    private val saveIntervalDuration = Duration(saveIntervalMillis, TimeUnit.MILLISECONDS)
    private val consecutiveSaveFails = LongRef.zeroSync


    private val setNeedSave = effectTotal(needSave.lazySet(true))
    private val save = (renameLock.withPermit(get.flatMap(saveNotebook)) *> effectTotal(needSave.lazySet(false)))
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

    private def init(): URIO[BaseEnv with GlobalEnv, Unit] = {
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

      // process all the submitted updates into the ref in order
      val processPending = pending.take.flatMap {
        case Take.Value((update, completerOpt)) =>
          (doUpdate(update) >>= notifyCompleter(completerOpt)).asSomeError
        case Take.End                           => pending.shutdown *> closed.succeed(()).unit <* ZIO.fail(None)
      }.forever.flip

      // every interval, check if the notebook needs to be written and do so
      val saveAtInterval =
        get.map(_.path).flatMap(path => Logging.info(s"Will write to $path")) *>
          save.whenM(effectTotal(needSave.get())).repeat(Schedule.fixed(saveIntervalDuration))

      // save one more time once closed
      val finalSave = save *> get.map(_.path).flatMap(path => Logging.info(s"Stopped writing to $path")) <* verify

      (processPending.unit.raceFirst(saveAtInterval.unit) *> finalSave).forkDaemon.flatMap(process.succeed).unit
    }
  }

  private object FileNotebookRef {

    def apply(notebook: Notebook, version: Int): URIO[BaseEnv with GlobalEnv, FileNotebookRef] = for {
      log        <- Logging.access
      current    <- Ref.make(version -> notebook)
      closed     <- Promise.make[Throwable, Unit]
      pending    <- Queue.unbounded[Take[Nothing, (NotebookUpdate, Option[Promise[Nothing, (Int, Notebook)]])]]
      renameLock <- Semaphore.make(1L)
      process    <- Promise.make[Nothing, Fiber[Nothing, Unit]]
      ref         = new FileNotebookRef(current, pending, closed, log, renameLock, process)
      _          <- ref.init()
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
    rawString <- encodeNotebook(nb)
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
