package polynote

import polynote.app.MainArgs
import polynote.kernel.environment.{BroadcastAll, Config, PublishMessage}
import polynote.kernel.logging.Logging
import polynote.kernel.util.{RefMap, UPublish}
import polynote.kernel.{BaseEnv, GlobalEnv, Kernel, KernelBusyState}
import polynote.messages.{CreateNotebook, DeleteNotebook, Message, NotebookCell, NotebookSearchResult, RenameNotebook, ShortString, fsNotebook}
import polynote.server.auth.{IdentityProvider, UserIdentity}
import polynote.server.repository.format.NotebookFormat
import polynote.server.repository.fs.FileSystems
import polynote.server.repository.{NotebookContent, NotebookRepository}
import scodec.bits.ByteVector
import uzhttp.websocket._
import zio.ZIO.not
import zio.clock.Clock
import zio.duration.Duration
import zio.stream.{Take, ZStream}
import zio.{Fiber, Has, Promise, Queue, RIO, RManaged, Ref, Schedule, Semaphore, Task, UIO, URIO, ZIO, ZLayer}

import java.net.URI
import java.nio.file.{AccessDeniedException, FileAlreadyExistsException, Paths}
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.SECONDS

package object server {
  type SessionEnv = BaseEnv with GlobalEnv with UserIdentity with IdentityProvider
  type AppEnv = BaseEnv with GlobalEnv with MainArgs with FileSystems with Has[NotebookRepository]

  def toFrame(message: Message): ZIO[Logging, Throwable, Binary] =
    Message.encode(message).map(bits => Binary(bits.toByteArray)).onError {
      err => Logging.error(err)
    }

  def toFrames[R](stream: ZStream[R, Throwable, Message]): ZStream[R with Logging, Throwable, Binary] =
    stream.mapM(toFrame)


  // Some ZIO syntax sugar
  implicit class ZIOBooleanOps[-R, +E](val self: ZIO[R, E, Boolean]) extends AnyVal {
    def &&[R1 <: R, E1 >: E](that: ZIO[R1, E1, Boolean]): ZIO[R1, E1, Boolean] =
      for {
        a <- self
        b <- that
      } yield a && b

    def unary_! : ZIO[R, E, Boolean] = not(self)
  }

  implicit class FrameStreamOps[R](val self: ZStream[R, Throwable, Frame]) extends AnyVal {
    def handleMessages[R1 <: Logging with R, A](onClose: ZIO[R, Throwable, Any])(fn: Message => ZIO[R1, Throwable, Option[Message]]): ZStream[R1, Throwable, Frame] =
      ZStream.fromEffect(Ref.make[ByteVector](ByteVector.empty)).flatMap {
        buf =>
          self.mapM {
            case Binary(data, true) =>
              Message.decode(ByteVector(data))
                .flatMap(fn).flatMap {
                case Some(msg) => toFrame(msg).asSome
                case None => ZIO.none
              }
            case Binary(data, false) => buf.set(ByteVector(data)).as(None)
            case Continuation(data, false) => buf.update(_ ++ ByteVector(data)).as(None)
            case Continuation(data, true) =>
              buf.getAndSet(ByteVector.empty).flatMap {
                buffered => Message.decode(buffered ++ ByteVector(data)).flatMap(fn).flatMap {
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
          }.ensuring {
            onClose.catchAll {
              err => Logging.error("Websocket close handler failed", err)
            }
          }.collectSome
      }
  }

  def closeQueueIf[A](promise: Promise[Throwable, Unit], queue: Queue[Take[Nothing, A]]): UIO[Unit] =
    promise.succeed(()).flatMap {
      case true => queue.offer(Take.end).unit
      case false => ZIO.unit
    }

  def closeStream[A](closed: Promise[Throwable, Unit], queue: Queue[Take[Nothing, A]]): ZStream[Any, Nothing, Nothing] =
    ZStream.fromEffect(closeQueueIf(closed, queue)).drain

  def keepaliveStream(closed: Promise[Throwable, Unit]): ZStream[Clock, Throwable, Frame] =
    ZStream.fromSchedule(Schedule.fixed(zio.duration.Duration(10, SECONDS)).as(Ping)).interruptWhen(closed)

  def parallelStreams[R, E, A](streams: ZStream[R, E, A]*): ZStream[R, E, A] =
    ZStream(streams: _*).flattenParUnbounded(streams.size).catchAllCause(_ => ZStream.empty)

  type NotebookManager = Has[NotebookManager.Service]

  object NotebookManager {

    def assertValidPath(path: String): RIO[NotebookManager with BaseEnv with GlobalEnv, Unit] = ZIO {
      require(!path.startsWith("/"), "Path must not have a leading slash (/)")
      val _ = Paths.get(path)
      ()
    }

    def access: URIO[NotebookManager, Service] = ZIO.access[NotebookManager](_.get)
    def open(path: String): RIO[NotebookManager with BaseEnv with GlobalEnv with BroadcastAll, KernelPublisher] = access.flatMap(_.open(path))
    def getKernel(path: String): URIO[NotebookManager, Option[Kernel]] = access.flatMap(_.getKernel(path))
    def subscribe(path: String): RManaged[NotebookManager with BaseEnv with GlobalEnv with PublishMessage with BroadcastAll with UserIdentity, KernelSubscriber] = open(path).toManaged_.flatMap(_.subscribe())
    def fetchIfOpen(path: String): RIO[NotebookManager with BaseEnv with GlobalEnv, Option[(String, String)]] = access.flatMap(_.fetchIfOpen(path))
    def location(path: String): RIO[NotebookManager with BaseEnv with GlobalEnv, Option[URI]] = access.flatMap(_.location(path))
    def list(): RIO[NotebookManager with BaseEnv with GlobalEnv, List[fsNotebook]] = access.flatMap(_.list())
    def listRunning(): RIO[NotebookManager with BaseEnv with GlobalEnv, List[String]] = access.flatMap(_.listRunning())
    def status(path: String): RIO[NotebookManager with BaseEnv with GlobalEnv, KernelBusyState] = access.flatMap(_.status(path))
    def create(path: String, maybeContent: Option[String]): RIO[NotebookManager with BaseEnv with GlobalEnv, String] = access.flatMap(_.create(path, maybeContent))
    def rename(path: String, newPath: String): RIO[NotebookManager with BaseEnv with GlobalEnv with BroadcastAll, String] = access.flatMap(_.rename(path, newPath))
    def copy(path: String, newPath: String): RIO[NotebookManager with BaseEnv with GlobalEnv, String] = access.flatMap(_.copy(path, newPath))
    def delete(path: String): RIO[NotebookManager with BaseEnv with GlobalEnv, Unit] = access.flatMap(_.delete(path))
    def search(query: String): RIO[NotebookManager with BaseEnv with GlobalEnv, List[NotebookSearchResult]] = access.flatMap(_.search(query))

    trait Service {
      def open(path: String): RIO[BaseEnv with GlobalEnv with BroadcastAll, KernelPublisher]
      def getKernel(path: String): UIO[Option[Kernel]]
      def fetchIfOpen(path: String): RIO[BaseEnv with GlobalEnv, Option[(String, String)]]
      def location(path: String): RIO[BaseEnv with GlobalEnv, Option[URI]]
      def list(): RIO[BaseEnv with GlobalEnv, List[fsNotebook]]
      def listRunning(): RIO[BaseEnv with GlobalEnv, List[String]]
      def status(path: String): RIO[BaseEnv with GlobalEnv, KernelBusyState]
      def create(path: String, maybeContent: Option[String]): RIO[BaseEnv with GlobalEnv, String]
      def rename(path: String, newPath: String): RIO[BaseEnv with GlobalEnv with BroadcastAll, String]
      def copy(path: String, newPath: String): RIO[BaseEnv with GlobalEnv, String]
      def delete(path: String): RIO[BaseEnv with GlobalEnv, Unit]
      def close(): RIO[BaseEnv, Unit]
      def search(query: String): RIO[BaseEnv with GlobalEnv, List[NotebookSearchResult]]
    }

    object Service {

      def apply(broadcastAll: UPublish[Message]): RIO[BaseEnv with GlobalEnv with Has[NotebookRepository], Service] =
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
        broadcastAll: UPublish[Message],
        moveLock: Semaphore
      ) extends Service {

        private val maxRetryDelay = Duration(8, TimeUnit.SECONDS)

        def getKernel(path: String): UIO[Option[Kernel]] = openNotebooks.get(path).some.flatMap(_.kernelIfStarted.some).asSome.orElse(ZIO.none)

        override def open(path: String): RIO[BaseEnv with GlobalEnv with BroadcastAll, KernelPublisher] = openNotebooks.getOrCreate(path) {
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

        override def list(): RIO[BaseEnv with GlobalEnv, List[fsNotebook]] = repository.listNotebooks()

        override def listRunning(): RIO[BaseEnv, List[String]] = openNotebooks.keys

        /**
          * Broadcast a [[Message]] to *all* active clients connected to this server. Used for messages that are NOT specific
          * to a given notebook or kernel.
          */
        private def broadcastMessage(m: Message): Task[Unit] = broadcastAll.publish(m)

        override def create(path: String, maybeContent: Option[String]): RIO[BaseEnv with GlobalEnv, String] =
          for {
            realPath <- repository.createNotebook(path, maybeContent)
            _        <- broadcastMessage(CreateNotebook(ShortString(realPath)))
          } yield realPath

        override def rename(path: String, newPath: String): RIO[BaseEnv with GlobalEnv with BroadcastAll, String] =
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

        override def search(query: String): RIO[BaseEnv with GlobalEnv, List[NotebookSearchResult]] = {
          repository.listNotebooks.flatMap(nbs => ZIO.foreachParN(16)(nbs) { nb => {
            for {
              loadedNB <- repository.loadNotebook(nb.path)
              cells    <- ZIO(loadedNB.cells.filter(c => c.content.toString.contains(query)))
            } yield for {
              cell <- cells
            } yield NotebookSearchResult(loadedNB.path, cell.id, cell.content.toString)
          }}).map(_.flatten)
        }

        def close(): RIO[BaseEnv, Unit] = openNotebooks.values.flatMap {
          notebooks => ZIO.foreachPar_(notebooks) {
            publisher => publisher.close()
          }
        }
      }
    }

    def apply(broadcastAll: UPublish[Message]): RIO[BaseEnv with GlobalEnv, NotebookManager.Service] =
      Service(broadcastAll).provideSomeLayer[BaseEnv with GlobalEnv](ZLayer.identity[Config] ++ FileSystems.live >>> NotebookRepository.live)

    def layer[R <: BaseEnv with GlobalEnv](broadcastAll: UPublish[Message]): ZLayer[R, Throwable, NotebookManager] =
      ZLayer.fromManaged(apply(broadcastAll).toManaged(_.close().orDie))
  }

}
