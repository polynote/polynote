package polynote.server

import java.io.File

import cats.effect.ConcurrentEffect
import polynote.kernel.environment.Config
import polynote.kernel.{BaseEnv, GlobalEnv, KernelBusyState, LocalKernel}
import polynote.kernel.util.{OptionEither, RefMap}
import polynote.messages.{Notebook, NotebookUpdate}
import polynote.server.repository.NotebookRepository
import polynote.server.repository.ipynb.IPythonNotebookRepository
import zio.blocking.Blocking
import zio.{TaskR, ZIO}
import zio.interop.catz._

import KernelPublisher.SubscriberId

trait NotebookManager {
  val notebookManager: NotebookManager.Service
}

object NotebookManager {

  def access: TaskR[NotebookManager, Service] = ZIO.access[NotebookManager](_.notebookManager)

  trait Service {
    def open(path: String): TaskR[BaseEnv with GlobalEnv, KernelPublisher]
    def list(): TaskR[BaseEnv with GlobalEnv, List[String]]
    def listRunning(): TaskR[BaseEnv with GlobalEnv, List[String]]
    def status(path: String): TaskR[BaseEnv with GlobalEnv, KernelBusyState]
    def create(path: String, maybeUriOrContent: Option[Either[String, String]]): TaskR[BaseEnv with GlobalEnv, String]
  }

  object Service {

    def apply(repository: NotebookRepository[TaskR[BaseEnv, ?]]): TaskR[BaseEnv, Service] = RefMap.empty[String, KernelPublisher].map {
      openNotebooks =>new Impl(openNotebooks, repository)
    }

    private class Impl(
      openNotebooks: RefMap[String, KernelPublisher],
      repository: NotebookRepository[TaskR[BaseEnv, ?]]
    ) extends Service {
      def open(path: String): TaskR[BaseEnv with GlobalEnv, KernelPublisher] = openNotebooks.getOrCreate(path) {
        for {
          notebook  <- repository.loadNotebook(path)
          publisher <- KernelPublisher(notebook)
          writer    <- publisher.notebooks
            .evalMap(notebook => repository.saveNotebook(notebook.path, notebook))
            .compile.drain.fork
        } yield publisher
      }

      def list(): TaskR[BaseEnv, List[String]] = repository.listNotebooks()

      def listRunning(): TaskR[BaseEnv, List[String]] = openNotebooks.keys

      def create(path: String, maybeUriOrContent: Option[Either[String, String]]): TaskR[BaseEnv, String] =
        repository.createNotebook(path, OptionEither.wrap(maybeUriOrContent))

      override def status(path: String): TaskR[BaseEnv with GlobalEnv, KernelBusyState] = openNotebooks.get(path).flatMap {
        case None => ZIO.succeed(KernelBusyState(busy = false, alive = false))
        case Some(publisher) => publisher.kernelStatus()
      }
    }
  }

  def apply()(implicit ev: ConcurrentEffect[TaskR[BaseEnv, ?]]): TaskR[BaseEnv with GlobalEnv, NotebookManager] = for {
    config    <- Config.access
    blocking  <- ZIO.accessM[Blocking](_.blocking.blockingExecutor)
    repository = new IPythonNotebookRepository[TaskR[BaseEnv, ?]](
      new File(System.getProperty("user.dir")).toPath.resolve(config.storage.dir),
      config,
      executionContext = blocking.asEC
    )
    service   <- Service(repository)
  } yield new NotebookManager {
    val notebookManager: Service = service
  }
}
