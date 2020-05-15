package polynote.server.repository

import java.io.{File, FileNotFoundException, IOException}
import java.net.URI
import java.nio.file.{Path, Paths}

import cats.implicits._
import polynote.config.{Mount, PolynoteConfig, Wal}
import polynote.kernel.NotebookRef.AlreadyClosed
import polynote.kernel.environment.Config
import polynote.kernel.{BaseEnv, GlobalEnv, NotebookRef, Result}
import polynote.messages._
import polynote.server.repository.fs.FileSystems
import zio.{Has, IO, Promise, RIO, Ref, Semaphore, Task, UIO, URIO, URLayer, ZIO, ZLayer}


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
      rootNBs  <- root.listNotebooks().map(_.map(deslash))
      mountNbs <- ZIO.foreach(repos.toList) {
        case (base, repo) => repo.listNotebooks().map(_.map(nbPath => deslash(Paths.get(base, nbPath).toString)))
      }
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
      _ <- ZIO.foreach_(repos.values)(_.initStorage())
  } yield ()
}
