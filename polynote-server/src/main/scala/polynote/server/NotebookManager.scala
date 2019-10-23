package polynote.server

import java.io.File
import java.util.concurrent.TimeUnit

import cats.effect.ConcurrentEffect
import polynote.kernel.environment.Config
import polynote.kernel.{BaseEnv, GlobalEnv, KernelBusyState, LocalKernel}
import polynote.kernel.util.RefMap
import polynote.messages.{Notebook, NotebookUpdate}
import polynote.server.repository.NotebookRepository
import polynote.server.repository.ipynb.IPythonNotebookRepository
import zio.blocking.Blocking
import zio.{RIO, UIO, ZIO}
import zio.interop.catz._
import KernelPublisher.SubscriberId

import scala.concurrent.duration.Duration

trait NotebookManager {
  val notebookManager: NotebookManager.Service
}

object NotebookManager {

  def access: RIO[NotebookManager, Service] = ZIO.access[NotebookManager](_.notebookManager)

  trait Service {
    def open(path: String): RIO[BaseEnv with GlobalEnv, KernelPublisher]
    def list(): RIO[BaseEnv with GlobalEnv, List[String]]
    def listRunning(): RIO[BaseEnv with GlobalEnv, List[String]]
    def status(path: String): RIO[BaseEnv with GlobalEnv, KernelBusyState]
    def create(path: String, maybeUriOrContent: Option[Either[String, String]]): RIO[BaseEnv with GlobalEnv, String]
  }

  object Service {

    def apply(repository: NotebookRepository[RIO[BaseEnv, ?]]): RIO[BaseEnv, Service] =
      repository.initStorage() *> RefMap.empty[String, KernelPublisher].map {
        openNotebooks => new Impl(openNotebooks, repository)
      }

    private class Impl(
      openNotebooks: RefMap[String, KernelPublisher],
      repository: NotebookRepository[RIO[BaseEnv, ?]]
    ) extends Service {
      def open(path: String): RIO[BaseEnv with GlobalEnv, KernelPublisher] = openNotebooks.getOrCreate(path) {
        for {
          notebook  <- repository.loadNotebook(path)
          publisher <- KernelPublisher(notebook)
          // write the notebook every 1 second, if it's changed.
          writer    <- publisher.notebooksTimed(Duration(1, TimeUnit.SECONDS))
              .evalMap(notebook => repository.saveNotebook(notebook.path, notebook))
              .compile.drain.fork
          onClose   <- publisher.closed.await.flatMap(_ => openNotebooks.remove(path)).fork
        } yield publisher
      }

      def list(): RIO[BaseEnv, List[String]] = repository.listNotebooks()

      def listRunning(): RIO[BaseEnv, List[String]] = openNotebooks.keys

      def create(path: String, maybeUriOrContent: Option[Either[String, String]]): RIO[BaseEnv, String] =
        repository.createNotebook(path, maybeUriOrContent)

      override def status(path: String): RIO[BaseEnv with GlobalEnv, KernelBusyState] = openNotebooks.get(path).flatMap {
        case None => ZIO.succeed(KernelBusyState(busy = false, alive = false))
        case Some(publisher) => publisher.kernelStatus()
      }
    }
  }

  def apply()(implicit ev: ConcurrentEffect[RIO[BaseEnv, ?]]): RIO[BaseEnv with GlobalEnv, NotebookManager] = for {
    config    <- Config.access
    blocking  <- ZIO.accessM[Blocking](_.blocking.blockingExecutor)
    repository = new IPythonNotebookRepository[RIO[BaseEnv, ?]](
      new File(System.getProperty("user.dir")).toPath.resolve(config.storage.dir),
      config,
      executionContext = blocking.asEC
    )
    service   <- Service(repository)
  } yield new NotebookManager {
    val notebookManager: Service = service
  }
}
