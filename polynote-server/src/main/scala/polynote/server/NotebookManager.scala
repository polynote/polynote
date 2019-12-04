package polynote.server

import java.io.File
import java.net.URI
import java.nio.file.{AccessDeniedException, FileAlreadyExistsException, Path}
import java.util.concurrent.TimeUnit

import cats.effect.ConcurrentEffect
import polynote.kernel.{BaseEnv, GlobalEnv, KernelBusyState, LocalKernel}
import polynote.kernel.util.RefMap
import polynote.messages.{CreateNotebook, DeleteNotebook, Message, Notebook, NotebookUpdate, RenameNotebook, ShortString}
import polynote.server.repository.{FileBasedRepository, NotebookRepository, TreeRepository}
import polynote.server.repository.format.ipynb.IPythonFormat
import zio.{Fiber, Promise, RIO, Task, UIO, ZIO}
import zio.interop.catz._
import cats.implicits._
import fs2.concurrent.Topic
import polynote.config.{Mount, PolynoteConfig}
import polynote.kernel.environment.Config
import polynote.kernel.logging.Logging
import zio.blocking.Blocking

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

trait NotebookManager {
  val notebookManager: NotebookManager.Service
}

object NotebookManager {

  def access: RIO[NotebookManager, Service] = ZIO.access[NotebookManager](_.notebookManager)

  trait Service {
    def open(path: String): RIO[BaseEnv with GlobalEnv, KernelPublisher]
    def location(path: String): RIO[BaseEnv with GlobalEnv, Option[URI]]
    def list(): RIO[BaseEnv with GlobalEnv, List[String]]
    def listRunning(): RIO[BaseEnv with GlobalEnv, List[String]]
    def status(path: String): RIO[BaseEnv with GlobalEnv, KernelBusyState]
    def create(path: String, maybeContent: Option[String]): RIO[BaseEnv with GlobalEnv, String]
    def rename(path: String, newPath: String): RIO[BaseEnv with GlobalEnv, String]
    def delete(path: String): RIO[BaseEnv with GlobalEnv, Unit]
  }

  object Service {

    def apply(repository: NotebookRepository, broadcastAll: Topic[Task, Option[Message]]): RIO[BaseEnv with GlobalEnv, Service] =
      repository.initStorage() *> RefMap.empty[String, (KernelPublisher, NotebookWriter)].map {
        openNotebooks => new Impl(openNotebooks, repository, broadcastAll)
      }

    private case class NotebookWriter(fiber: Fiber[Throwable, Unit], shutdownSignal: Promise[Throwable, Unit]) {
      def stop(): Task[Unit] = shutdownSignal.succeed(()) *> fiber.join
    }

    private class Impl(
      openNotebooks: RefMap[String, (KernelPublisher, NotebookWriter)],
      repository: NotebookRepository,
      broadcastAll: Topic[Task, Option[Message]]
    ) extends Service {

      // write the notebook every 1 second, if it's changed.
      private def startWriter(publisher: KernelPublisher): ZIO[BaseEnv with GlobalEnv, Nothing, NotebookWriter] = for {
        shutdownSignal <- Promise.make[Throwable, Unit]
        fiber          <- publisher.notebooksTimed(Duration(1, TimeUnit.SECONDS))
          .evalMap(notebook => repository.saveNotebook(notebook))
          .interruptWhen(shutdownSignal.await.either)
          .interruptWhen(publisher.closed.await.either)
          .compile.drain.fork
      } yield NotebookWriter(fiber, shutdownSignal)

      override def open(path: String): RIO[BaseEnv with GlobalEnv, KernelPublisher] = openNotebooks.getOrCreate(path) {
        for {
          notebook      <- repository.loadNotebook(path)
          publisher     <- KernelPublisher(notebook)
          writer        <- startWriter(publisher)
          onClose       <- publisher.closed.await.flatMap(_ => openNotebooks.remove(path)).fork
        } yield (publisher, writer)
      }.flatMap {
        case (publisher, writer) => publisher.closed.isDone.flatMap {
          case true  => open(path)
          case false => ZIO.succeed(publisher)
        }
      }

      override def location(path: String): RIO[BaseEnv with GlobalEnv, Option[URI]] = repository.notebookURI(path)

      override def list(): RIO[BaseEnv with GlobalEnv, List[String]] = repository.listNotebooks()

      override def listRunning(): RIO[BaseEnv, List[String]] = openNotebooks.keys

      override def create(path: String, maybeContent: Option[String]): RIO[BaseEnv with GlobalEnv, String] =
        repository.createNotebook(path, maybeContent).flatMap {
          actualPath => (broadcastAll.publish1(Some(CreateNotebook(ShortString(actualPath)))) *> broadcastAll.publish1(None)).as(actualPath)
        }

      override def rename(path: String, newPath: String): RIO[BaseEnv with GlobalEnv, String] =
        openNotebooks.get(path).flatMap {
          case None                      => repository.renameNotebook(path, newPath)
          case Some((publisher, writer)) => repository.notebookExists(newPath).flatMap {
            case true  => ZIO.fail(new FileAlreadyExistsException(s"File $newPath already exists"))
            case false => // if the notebook is already open, we have to stop writing, rename, and start writing again
              writer.stop() *> repository.renameNotebook(path, newPath).foldM(
                err => startWriter(publisher) *> Logging.error("Unable to rename notebook", err) *> ZIO.fail(err),
                realPath => publisher.rename(realPath).as(realPath) *> startWriter(publisher).flatMap {
                  writer => openNotebooks.put(path, (publisher, writer)).as(realPath)
                }
              )
          }
        }.flatMap {
          realPath => broadcastAll.publish1(Some(RenameNotebook(path, realPath))).as(realPath) <* broadcastAll.publish1(None)
        }

      override def delete(path: String): RIO[BaseEnv with GlobalEnv, Unit] =
        openNotebooks.get(path).flatMap {
          case Some(_) => ZIO.fail(new AccessDeniedException(path, null, "Notebook cannot be deleted while it is open"))
          case None    => repository.deleteNotebook(path) *> broadcastAll.publish1(Some(DeleteNotebook(path))) *> broadcastAll.publish1(None)
        }

      override def status(path: String): RIO[BaseEnv with GlobalEnv, KernelBusyState] = openNotebooks.get(path).flatMap {
        case None => ZIO.succeed(KernelBusyState(busy = false, alive = false))
        case Some((publisher, _)) => publisher.kernelStatus()
      }
    }
  }

  private def makeTreeRepository(dir: String, mounts: Map[String, Mount], config: PolynoteConfig, ec: ExecutionContext): TreeRepository = {
    val repoMap = mounts.mapValues {
      mount =>
        makeTreeRepository(mount.dir, mount.mounts, config, ec)
    }
    val rootRepo = new FileBasedRepository(new File(System.getProperty("user.dir")).toPath.resolve(dir))

    new TreeRepository(rootRepo, repoMap)
  }

  def apply(broadcastAll: Topic[Task, Option[Message]]): RIO[BaseEnv with GlobalEnv, NotebookManager] = for {
    config    <- Config.access
    blocking  <- ZIO.accessM[Blocking](_.blocking.blockingExecutor)
    repository = makeTreeRepository(config.storage.dir, config.storage.mounts, config, blocking.asEC)
    service   <- Service(repository, broadcastAll)
  } yield new NotebookManager {
    val notebookManager: Service = service
  }
}
