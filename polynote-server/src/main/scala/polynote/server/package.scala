package polynote

import java.io.File
import java.net.URI
import java.nio.file.{AccessDeniedException, FileAlreadyExistsException}
import java.util.concurrent.TimeUnit

import cats.{Applicative, MonadError}
import cats.effect.{Concurrent, ConcurrentEffect, ContextShift, Timer}
import fs2.concurrent.Topic
import org.http4s.websocket.WebSocketFrame.Binary
import polynote.config.{Mount, PolynoteConfig}
import polynote.kernel.environment.{Config, PublishMessage}
import polynote.kernel.logging.Logging
import polynote.kernel.util.RefMap
import polynote.kernel.{BaseEnv, GlobalEnv, KernelBusyState, TaskB, TaskG}
import polynote.messages.{CreateNotebook, DeleteNotebook, Error, Message, RenameNotebook, ShortString}
import polynote.server.auth.{IdentityProvider, UserIdentity}
import polynote.server.repository.{FileBasedRepository, NotebookRepository, TreeRepository}
import zio.blocking.Blocking
import zio.duration.Duration
import zio.{Fiber, Has, Promise, RIO, Schedule, Task, UIO, ZIO}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

package object server {
  type SessionEnv = BaseEnv with GlobalEnv with UserIdentity with IdentityProvider

  // some cached typeclass instances
  import zio.interop.{catz => interop}
  implicit val taskTimer: Timer[Task] = interop.implicits.ioTimer[Throwable]
  implicit val taskMonadError: MonadError[Task, Throwable] = interop.monadErrorInstance[Any, Throwable]
  implicit val taskConcurrent: Concurrent[Task] = interop.taskConcurrentInstance[Any]
  implicit val taskBConcurrent: Concurrent[TaskB] = interop.taskConcurrentInstance[BaseEnv]
  implicit val taskGConcurrent: Concurrent[TaskG] = interop.taskConcurrentInstance[BaseEnv with GlobalEnv]
  implicit val sessionConcurrent: Concurrent[RIO[SessionEnv, ?]] = interop.taskConcurrentInstance[SessionEnv]
  implicit val contextShiftTask: ContextShift[Task] = interop.zioContextShift[Any, Throwable]
  implicit val uioConcurrent: Concurrent[UIO] = interop.taskConcurrentInstance[Any].asInstanceOf[Concurrent[UIO]]
  implicit val rioApplicativeGlobal: Applicative[TaskG] = interop.taskConcurrentInstance[BaseEnv with GlobalEnv]
  implicit val rioApplicativePublishMessage: Applicative[RIO[PublishMessage, ?]] = interop.taskConcurrentInstance[PublishMessage]

  def toFrame(message: Message): Task[Binary] = {
    Message.encode[Task](message).map(bits => Binary(bits.toByteVector))
  }

  type NotebookManager = Has[NotebookManager.Service]

  object NotebookManager {

    def access: RIO[NotebookManager, Service] = ZIO.access[NotebookManager](_.get)
    def open(path: String): RIO[NotebookManager with BaseEnv with GlobalEnv, KernelPublisher] = access.flatMap(_.open(path))

    trait Service {
      def open(path: String): RIO[BaseEnv with GlobalEnv, KernelPublisher]
      def location(path: String): RIO[BaseEnv with GlobalEnv, Option[URI]]
      def list(): RIO[BaseEnv with GlobalEnv, List[String]]
      def listRunning(): RIO[BaseEnv with GlobalEnv, List[String]]
      def status(path: String): RIO[BaseEnv with GlobalEnv, KernelBusyState]
      def create(path: String, maybeContent: Option[String]): RIO[BaseEnv with GlobalEnv, String]
      def rename(path: String, newPath: String): RIO[BaseEnv with GlobalEnv, String]
      def copy(path: String, newPath: String): RIO[BaseEnv with GlobalEnv, String]
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

        private val maxRetryDelay = Duration(8, TimeUnit.SECONDS)

        // write the notebook every 1 second, if it's changed.
        private def startWriter(publisher: KernelPublisher): ZIO[BaseEnv with GlobalEnv, Nothing, NotebookWriter] = for {
          shutdownSignal <- Promise.make[Throwable, Unit]
            nbPath          = publisher.latestVersion.map(_._2.path).orDie
            fiber          <- publisher.notebooks.debounce(1.second).evalMap {
              notebook => repository.saveNotebook(notebook)
                .tapError(Logging.error("Error writing notebook file", _))
                .retry(Schedule.exponential(Duration(250, TimeUnit.MILLISECONDS)).untilOutput(_ > maxRetryDelay))
                .tapError(err =>
                  nbPath.flatMap(path => broadcastMessage(Error(0, new Exception(s"Notebook writer for $path is repeatedly failing! Notebook editing will be disabled.", err))) *> publisher.close()))
                .onInterrupt(nbPath.flatMap(path => Logging.info(s"Stopped writer for $path (interrupted)")))
            }.interruptAndIgnoreWhen(shutdownSignal).interruptAndIgnoreWhen(publisher.closed).onFinalize {
              nbPath.flatMap(path => Logging.info(s"Stopped writer for $path (interrupted)"))
            }.compile.drain.forkDaemon
        } yield NotebookWriter(fiber, shutdownSignal)

        override def open(path: String): RIO[BaseEnv with GlobalEnv, KernelPublisher] = openNotebooks.getOrCreate(path) {
          for {
            notebook      <- repository.loadNotebook(path)
              publisher     <- KernelPublisher(notebook, broadcastAll)
              writer        <- startWriter(publisher)
              onClose       <- publisher.closed.await.flatMap(_ => openNotebooks.remove(path)).forkDaemon
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

        /**
          * Broadcast a [[Message]] to *all* active clients connected to this server. Used for messages that are NOT specific
          * to a given notebook or kernel.
          */
        private def broadcastMessage(m: Message): Task[Unit] = broadcastAll.publish1(Some(m)) *> broadcastAll.publish1(None)

        override def create(path: String, maybeContent: Option[String]): RIO[BaseEnv with GlobalEnv, String] =
          for {
            realPath <- repository.createNotebook(path, maybeContent)
              _        <- broadcastMessage(CreateNotebook(ShortString(realPath)))
          } yield realPath

        override def rename(path: String, newPath: String): RIO[BaseEnv with GlobalEnv, String] =
          for {
            realPath <- openNotebooks.get(path).flatMap {
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
            }
              _ <- broadcastMessage(RenameNotebook(path, realPath))
          } yield realPath

        override def copy(path: String, newPath: String): RIO[BaseEnv with GlobalEnv, String] = for {
          realPath <- repository.copyNotebook(path, newPath)
            _        <- broadcastMessage(CreateNotebook(realPath, None))
        } yield realPath

        override def delete(path: String): RIO[BaseEnv with GlobalEnv, Unit] =
          openNotebooks.get(path).flatMap {
            case Some(_) => ZIO.fail(new AccessDeniedException(path, null, "Notebook cannot be deleted while it is open"))
            case None    => repository.deleteNotebook(path) *> broadcastMessage(DeleteNotebook(path))
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

    def apply(broadcastAll: Topic[Task, Option[Message]]): RIO[BaseEnv with GlobalEnv, NotebookManager.Service] = for {
      config    <- Config.access
        blocking  <- ZIO.access[Blocking](_.get[Blocking.Service].blockingExecutor)
        repository = makeTreeRepository(config.storage.dir, config.storage.mounts, config, blocking.asEC)
        service   <- Service(repository, broadcastAll)
    } yield service
  }
}
