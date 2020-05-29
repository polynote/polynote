package polynote

import java.io.File
import java.net.URI
import java.nio.file.{AccessDeniedException, FileAlreadyExistsException, Paths}
import java.util.concurrent.TimeUnit

import cats.{Applicative, MonadError}
import cats.effect.{Concurrent, ConcurrentEffect, ContextShift, Timer}
import fs2.concurrent.Topic
import polynote.app.MainArgs
import polynote.config.{Mount, PolynoteConfig}
import polynote.kernel.environment.{Config, PublishMessage}
import polynote.kernel.logging.Logging
import polynote.kernel.util.RefMap
import polynote.kernel.{BaseEnv, GlobalEnv, KernelBusyState, NotebookRef, TaskB, TaskG}
import polynote.messages.{CreateNotebook, DeleteNotebook, Error, Message, RenameNotebook, ShortString}
import polynote.server.auth.{IdentityProvider, UserIdentity}
import polynote.server.repository.fs.FileSystems
import polynote.server.repository.{FileBasedRepository, NotebookContent, NotebookRepository, TreeRepository}
import scodec.bits.ByteVector
import uzhttp.websocket.{Binary, Close, Continuation, Frame, Ping, Pong}
import zio.blocking.effectBlocking
import zio.clock.Clock
import zio.duration.Duration
import zio.stream.{Take, ZStream}
import zio.{Chunk, Fiber, Has, Promise, Queue, RIO, Ref, Schedule, Semaphore, Task, UIO, URIO, ZIO, ZLayer}
import polynote.server.repository.format.NotebookFormat

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{DurationInt, SECONDS}

package object server {
  type SessionEnv = BaseEnv with GlobalEnv with UserIdentity with IdentityProvider
  type AppEnv = BaseEnv with GlobalEnv with MainArgs with FileSystems with Has[NotebookRepository]

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

  def toFrame(message: Message): ZIO[Logging, Throwable, Binary] =
    Message.encode[Task](message).map(bits => Binary(bits.toByteArray)).onError {
      err => Logging.error(err)
    }

  def toFrames[R](stream: ZStream[R, Throwable, Message]): ZStream[R with Logging, Throwable, Binary] =
    stream.mapM(toFrame)

  implicit class FrameStreamOps[R](val self: ZStream[R, Throwable, Frame]) extends AnyVal {
    def handleMessages[R1 <: Logging with R, A](onClose: ZIO[R, Throwable, Any])(fn: Message => ZIO[R1, Throwable, Option[Message]]): ZStream[R1, Throwable, Frame] =
      ZStream.fromEffect(Ref.make[ByteVector](ByteVector.empty)).flatMap {
        buf =>
          self.mapM {
            case Binary(data, true) =>
              Message.decode[Task](ByteVector(data))
                .flatMap(fn).flatMap {
                case Some(msg) => toFrame(msg).asSome
                case None => ZIO.none
              }
            case Binary(data, false) => buf.set(ByteVector(data)).as(None)
            case Continuation(data, false) => buf.update(_ ++ ByteVector(data)).as(None)
            case Continuation(data, true) =>
              buf.getAndSet(ByteVector.empty).flatMap {
                buffered => Message.decode[Task](buffered ++ ByteVector(data)).flatMap(fn).flatMap {
                  case Some(msg) => toFrame(msg).asSome
                  case None => ZIO.none
                }
              }
            case Ping => ZIO.some(Pong)
            case Pong => ZIO.none
            case Close =>
              onClose.as(Some(Close))
            case _ => ZIO.none
          }.catchAll {
            err => ZStream.fromEffect(Logging.error(err)).drain
          }.unNone.ensuring {
            onClose.catchAll {
              err => Logging.error("Websocket close handler failed", err)
            }
          }
      }
  }

  def closeQueueIf[A](promise: Promise[Throwable, Unit], queue: Queue[Take[Nothing, A]]): UIO[Unit] =
    promise.succeed(()).flatMap {
      case true => queue.offer(Take.End).unit
      case false => ZIO.unit
    }

  def closeStream[A](closed: Promise[Throwable, Unit], queue: Queue[Take[Nothing, A]]): ZStream[Any, Nothing, Nothing] =
    ZStream.fromEffect(closeQueueIf(closed, queue)).drain

  def keepaliveStream(closed: Promise[Throwable, Unit]): ZStream[Clock, Throwable, Frame] =
    ZStream.fromSchedule(Schedule.fixed(zio.duration.Duration(10, SECONDS)).as(Ping)).interruptWhen(closed)

  def parallelStreams[R, E, A](streams: ZStream[R, E, A]*): ZStream[R, E, A] =
    ZStream.flattenPar(streams.size)(ZStream(streams: _*)).catchAllCause(_ => ZStream.empty)

  def streamEffects[R, E, A](effects: ZIO[R, E, A]*): ZStream[R, E, A] = ZStream.flatten(ZStream(effects.map(e => ZStream.fromEffect(e)): _*))

  type NotebookManager = Has[NotebookManager.Service]

  object NotebookManager {

    def assertValidPath(path: String): RIO[NotebookManager with BaseEnv with GlobalEnv, Unit] = ZIO {
      require(!path.startsWith("/"), "Path must not have a leading slash (/)")
      val _ = Paths.get(path)
      ()
    }

    def access: URIO[NotebookManager, Service] = ZIO.access[NotebookManager](_.get)
    def open(path: String): RIO[NotebookManager with BaseEnv with GlobalEnv, KernelPublisher] = access.flatMap(_.open(path))
    def fetchIfOpen(path: String): RIO[NotebookManager with BaseEnv with GlobalEnv, Option[(String, String)]] = access.flatMap(_.fetchIfOpen(path))
    def location(path: String): RIO[NotebookManager with BaseEnv with GlobalEnv, Option[URI]] = access.flatMap(_.location(path))
    def list(): RIO[NotebookManager with BaseEnv with GlobalEnv, List[String]] = access.flatMap(_.list())
    def listRunning(): RIO[NotebookManager with BaseEnv with GlobalEnv, List[String]] = access.flatMap(_.listRunning())
    def status(path: String): RIO[NotebookManager with BaseEnv with GlobalEnv, KernelBusyState] = access.flatMap(_.status(path))
    def create(path: String, maybeContent: Option[String]): RIO[NotebookManager with BaseEnv with GlobalEnv, String] = access.flatMap(_.create(path, maybeContent))
    def rename(path: String, newPath: String): RIO[NotebookManager with BaseEnv with GlobalEnv, String] = access.flatMap(_.rename(path, newPath))
    def copy(path: String, newPath: String): RIO[NotebookManager with BaseEnv with GlobalEnv, String] = access.flatMap(_.copy(path, newPath))
    def delete(path: String): RIO[NotebookManager with BaseEnv with GlobalEnv, Unit] = access.flatMap(_.delete(path))

    trait Service {
      def open(path: String): RIO[BaseEnv with GlobalEnv, KernelPublisher]
      def fetchIfOpen(path: String): RIO[BaseEnv with GlobalEnv, Option[(String, String)]]
      def location(path: String): RIO[BaseEnv with GlobalEnv, Option[URI]]
      def list(): RIO[BaseEnv with GlobalEnv, List[String]]
      def listRunning(): RIO[BaseEnv with GlobalEnv, List[String]]
      def status(path: String): RIO[BaseEnv with GlobalEnv, KernelBusyState]
      def create(path: String, maybeContent: Option[String]): RIO[BaseEnv with GlobalEnv, String]
      def rename(path: String, newPath: String): RIO[BaseEnv with GlobalEnv, String]
      def copy(path: String, newPath: String): RIO[BaseEnv with GlobalEnv, String]
      def delete(path: String): RIO[BaseEnv with GlobalEnv, Unit]
      def close(): RIO[BaseEnv, Unit]
    }

    object Service {

      def apply(broadcastAll: Topic[Task, Option[Message]]): RIO[BaseEnv with GlobalEnv with Has[NotebookRepository], Service] =
        ZIO.access[Has[NotebookRepository]](_.get[NotebookRepository]).flatMap {
          repository =>
            repository.initStorage() *> ZIO.mapN(RefMap.empty[String, KernelPublisher], Semaphore.make(1L)) {
              (openNotebooks, moveLock) => new Impl(openNotebooks, repository, broadcastAll, moveLock)
            }
        }

      private case class NotebookWriter(fiber: Fiber[Throwable, Unit], shutdownSignal: Promise[Throwable, Unit]) {
        def stop(): Task[Unit] = shutdownSignal.succeed(()) *> fiber.join
      }

      private class Impl(
        openNotebooks: RefMap[String, KernelPublisher],
        repository: NotebookRepository,
        broadcastAll: Topic[Task, Option[Message]],
        moveLock: Semaphore
      ) extends Service {

        private val maxRetryDelay = Duration(8, TimeUnit.SECONDS)


        override def open(path: String): RIO[BaseEnv with GlobalEnv, KernelPublisher] = openNotebooks.getOrCreate(path) {
          for {
            notebookRef   <- repository.openNotebook(path)
            publisher     <- KernelPublisher(notebookRef, broadcastAll)
            onClose       <- publisher.closed.await.flatMap(_ => publisher.versionedNotebook.path.flatMap(openNotebooks.remove)).forkDaemon
          } yield publisher
        }.flatMap {
          publisher => publisher.closed.isDone.flatMap {
            case true  => open(path)
            case false => ZIO.succeed(publisher)
          }
        }

        override def fetchIfOpen(path: String): RIO[BaseEnv with GlobalEnv, Option[(String, String)]] = {
          openNotebooks.get(path).flatMap {
            case None => ZIO.succeed(None)
            case Some(pub) =>
              for {
                (_, nb)   <- pub.latestVersion
                fmt       <- NotebookFormat.getFormat(Paths.get(nb.path))
                rawString <- fmt.encodeNotebook(NotebookContent(nb.cells, nb.config))
              } yield Some((fmt.mime, rawString))
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
              case None            => repository.renameNotebook(path, newPath)
              case Some(publisher) => moveLock.withPermit {
                repository.notebookExists(newPath).flatMap {
                  case true  => ZIO.fail(new FileAlreadyExistsException(s"File $newPath already exists"))
                  case false => publisher.versionedNotebook.rename(newPath).flatMap {
                    newPath => openNotebooks.put(newPath, publisher) *> openNotebooks.remove(path).as(newPath)
                  }
                }
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
          case Some(publisher) => publisher.kernelStatus()
        }

        def close(): RIO[BaseEnv, Unit] = openNotebooks.values.flatMap {
          notebooks => ZIO.foreachPar_(notebooks) {
            publisher => publisher.close()
          }
        }
      }
    }

    def apply(broadcastAll: Topic[Task, Option[Message]]): RIO[BaseEnv with GlobalEnv, NotebookManager.Service] =
      Service(broadcastAll).provideSomeLayer[BaseEnv with GlobalEnv](ZLayer.identity[Config] ++ FileSystems.live >>> NotebookRepository.live)

    def layer[R <: BaseEnv with GlobalEnv](broadcastAll: Topic[Task, Option[Message]]): ZLayer[R, Throwable, NotebookManager] =
      ZLayer.fromManaged(apply(broadcastAll).toManaged(_.close().orDie))
  }

}
