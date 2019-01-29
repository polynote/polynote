package polynote.server

import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiFunction

import cats.effect._
import cats.effect.concurrent.{MVar, Ref, Semaphore}
import cats.syntax.all._
import cats.instances.list._
import cats.~>
import fs2.Stream
import fs2.concurrent.{Queue, SignallingRef, Topic}
import org.http4s.server.websocket.WebSocketBuilder
import org.http4s.websocket.WebsocketBits._
import org.log4s.getLogger
import polynote.kernel._
import polynote.kernel.util.WindowBuffer
import polynote.messages._
import polynote.server.repository.NotebookRepository

import scala.concurrent.duration._

class SocketSession(
  repository: NotebookRepository[IO],
  router: KernelRouter[IO],
  inBufferLength: Int = 100,
  outBufferLength: Int = 100)(implicit
  contextShift: ContextShift[IO],
  timer: Timer[IO]
) {

  private val inbound = Queue.bounded[IO, WebSocketFrame](inBufferLength)
  private val outbound = Queue.bounded[IO, Message](outBufferLength)
  private[this] val logger = getLogger

  private val loadingNotebook = Semaphore[IO](1).unsafeRunSync()
  private val notebooks = new ConcurrentHashMap[String, Ref[IO, Notebook]]()
  private val notebookUpdates = new ConcurrentHashMap[String, Stream[IO, Notebook]]()
  private val writers = new ConcurrentHashMap[String, Fiber[IO, Unit]]()

  private def toFrame(message: Message) = {
    Message.encode[IO](message).map(bits => Binary(bits.toByteArray))
  }

  private def logMessage(message: Message): IO[Unit]= IO(logger.info(message.toString))
  private def logError(error: Throwable): IO[Unit] = IO(logger.error(error)(error.getMessage))

  lazy val toResponse = (inbound, outbound).parMapN {
    (iq, oq) =>
      val fromClient = iq.enqueue

      // TODO: handle continuations/non-final?

      /**
        * The evaluation semantics here are tricky. respond() is going to return a suspension which contains a
        * Stream with its own suspensions. We want to allow both the outer suspensions and the inner suspensions
        * to run in parallel, but we also want to make sure the outer suspensions are run in the order they were
        * received. So we start them as soon as they come in.
        *
        * TODO: can this be uncomplicated by having respond() just write to outbound queue rather than returning
        *       its own stream?
        */
      val responses = iq.dequeue.evalMap {
        case Binary(bytes, true) => Message.decode[IO](bytes).flatMap {
          message => respond(message, oq).start
        }

        case other => IO.pure(Stream.emit(badMsgErr(other))).start
      }.evalMap {
        fiber => fiber.join.uncancelable
      }

      val toClient: Stream[IO, WebSocketFrame] =
        Stream.awakeEvery[IO](10.seconds).map {
          d => Text("Ping!")  // Websocket is supposed to have its own keepalives, but this seems to prevent the connection from dying all the time while idle.
        }.merge {
          Stream(oq.dequeue, responses.parJoinUnbounded).parJoinUnbounded
            .handleErrorWith {
              err =>
                val re = err match {
                  case RuntimeError(e) => e
                  case e => RuntimeError(e)
                }
                Stream.eval(logError(re).map(_ => Error(0, re)))
            }
            //.evalTap(logMessage)
            .evalMap(toFrame).handleErrorWith {
              err =>
                Stream.eval(logError(err)).drain
            }
        }

      WebSocketBuilder[IO].build(toClient, fromClient)
  }.flatten

  private def getNotebookRef(path: String): Either[Throwable, Ref[IO, Notebook]] = Either.fromOption(
    Option(notebooks.get(path)) orElse router.getKernel(path).map(kernel => kernel.notebookRef),
    new RuntimeException(s"Notebook $path is not loaded") // TODO: handle better (or better error)
  )

  private def getNotebook(path: String): IO[Notebook] = for {
    ref      <- IO.fromEither(getNotebookRef(path))
    notebook <- ref.get
  } yield notebook

  private def loadNotebook(path: String): IO[Notebook] = loadingNotebook.acquire.bracket {_ =>
    for {
      _       <- if (getNotebookRef(path).isRight) IO.raiseError(new UnsupportedOperationException(s"$path is already loaded")) else IO.unit
      loaded  <- repository.loadNotebook(path)
      ref     <- SignallingRef[IO, Notebook](loaded)
      _        = notebooks.put(path, ref)
      updates  = ref.discrete
      _        = { notebookUpdates.put(path, updates); () }
      writer  <- updates.evalMap { nb =>
        // TODO: this code runs every time the nb is updated, maybe it should be moved somewhere more obvious?
        // TODO: bubble error up to user?
        repository.saveNotebook(path, nb).handleErrorWith { err =>
          logError(err)
        }
      }.compile.drain.start
      _        = { writers.put(path, writer); () }
    } yield loaded
  } {
    _ => loadingNotebook.release
  }

  private def getOrLoadNotebook(path: String): IO[Notebook] =
    if (notebooks.containsKey(path))
      notebooks.get(path).get
    else
      router.getKernel(path).map {
        kernel =>
          val ref = kernel.notebookRef
          for {
            notebook <- ref.get
            _        <- IO { notebooks.put(path, ref) }
          } yield notebook
      }.getOrElse(loadNotebook(path))

  private def updateNotebook(path: String)(fn: Notebook => Notebook) = for {
    notebookRef  <- IO.fromEither(getNotebookRef(path))
    _            <- notebookRef.update(fn)
    updated      <- notebookRef.get
  } yield updated

  private def getKernel(notebookRef: Ref[IO, Notebook], oq: Queue[IO, Message]): IO[Kernel[IO]] = notebookRef.get.flatMap {
    notebook => router.getKernel(notebook.path).fold {
      val taskInfo = TaskInfo("kernel", "Start", "Kernel starting", TaskStatus.Running)
      for {
        updates <- Topic[IO, KernelStatusUpdate](UpdatedTasks(taskInfo :: Nil))
        sub     <- updates.subscribe(256).map(KernelStatus(notebook.path, _)).to(oq.enqueue).compile.drain.start
        kernel  <- router.getOrCreateKernel(notebookRef, taskInfo, updates)
        _       <- updates.publish1(UpdatedTasks(TaskInfo("kernel", "Start", "Kernel started", TaskStatus.Complete, 255.toByte) :: Nil))
      } yield kernel
    } {
      kernel => for {
        _ <- kernel.statusUpdates.subscribe(256).map(KernelStatus(notebook.path, _)).to(oq.enqueue).compile.drain.start
      }  yield kernel
    }
  }

  private def forceRestartKernel(notebook: Notebook, oq: Queue[IO, Message]): Option[IO[Kernel[IO]]] = router.getKernel(notebook.path).map { prevKernel =>
    for {
      ref     <- IO.fromEither(getNotebookRef(notebook.path))
      updates <- IO.pure(prevKernel.statusUpdates)
      taskInfo = TaskInfo("kernel", "Restart", "Kernel restarting", TaskStatus.Running)
      _       <- updates.publish1(UpdatedTasks(taskInfo :: Nil))
      _       <- prevKernel.shutdown()
      _       <- updates.publish1(KernelBusyState(busy = true, alive = false))
      kernel  <- router.createKernel(ref, taskInfo, updates)
      _       <- updates.publish1(UpdatedTasks(taskInfo.copy(status = TaskStatus.Complete, progress = 255.toByte) :: Nil))
      _       <- updates.publish1(KernelBusyState(busy = false, alive = true))
    } yield kernel
  }

  private def kernelStatusInfo(path: ShortString, kernel: Kernel[IO]) =
    Stream.eval(kernel.currentSymbols().map(syms => KernelStatus(path, UpdatedSymbols(syms, Nil)))).merge {
      Stream.eval(kernel.currentTasks()).map(tasks => KernelStatus(path, UpdatedTasks(tasks)))
    }.merge {
      Stream.eval(kernel.idle().map(idle => KernelStatus(path, KernelBusyState(!idle, alive = true))))
    }

  def respond(message: Message, oq: Queue[IO, Message]): IO[Stream[IO, Message]] = message match {
    case ListNotebooks(_) =>
      repository.listNotebooks().map {
        notebooks => Stream.emit(ListNotebooks(notebooks.asInstanceOf[List[ShortString]]))
      }

    case LoadNotebook(path) =>
      getOrLoadNotebook(path).map { notebook =>
        Stream.emit(notebook) ++ router.getKernel(path)
          .fold[Stream[IO, Message]](Stream.emit(KernelStatus(path, KernelBusyState(busy = false, alive = false))))(
            kernelStatusInfo(path, _))
      }

    case CreateNotebook(path) =>
      repository.createNotebook(path).map {
        actualPath => Stream.emit(CreateNotebook(ShortString(actualPath)))
      }

    case InsertCell(path, cell, after) =>
      updateNotebook(path) {
        notebook =>
          val insertIndex = after.fold(0)(id => notebook.cells.indexWhere(_.id == id)) match {
            case -1 => 0
            case n => n
          }

          notebook.copy(
            cells = ShortList(
              notebook.cells.take(insertIndex + 1) ++ (cell :: notebook.cells.drop(insertIndex + 1))))
      }.map(_ => Stream.empty)

    case UpdateCell(path, id, edits) =>
      updateNotebook(path) {
        notebook => notebook.updateCell(id) {
          cell => cell.updateContent {
            content => edits.foldLeft(content) {
              (accum, next) => next.applyTo(accum)
            }
          }
        }
      }.map(_ => Stream.empty) // TODO: acknowledge?

    case DeleteCell(path, id) =>
      updateNotebook(path) {
        notebook => notebook.copy(cells = ShortList(notebook.cells.filter(_.id != id)))
      }.map(_ => Stream.empty)

    case UpdateConfig(path, config) =>
      val updateRef = updateNotebook(path) {
        notebook => notebook.copy(config = Option(config))
      }

      for {
        updated <- updateRef
        _       <- forceRestartKernel(updated, oq).map(_.map(_ => ())).getOrElse(IO.unit)
      } yield Stream.empty


    case RunCell(path, ids) =>
      def runOne(kernel: Kernel[IO], id: String) = {
        val buf = new WindowBuffer[Result](1000)
        Stream.eval(kernel.runCell(id)).flatten.evalMap {
          result => IO(buf.add(result)) >> IO.pure(CellResult(path, id, result))
        }.onFinalize(updateNotebook(path)(_.setResults(id, buf.toList)).as(()))
      }

      for {
        notebook <- IO.fromEither(getNotebookRef(path))
        kernel   <- getKernel(notebook, oq)
      } yield Stream.emits(ids.map(runOne(kernel, _))).flatten


    case req@CompletionsAt(notebook, id, pos, _) =>
      router.getKernel(notebook).fold(IO.pure(req)) {
        kernel => kernel.completionsAt(id, pos).map {
          result => req.copy(completions = ShortList(result))
        }.handleErrorWith(err => IO(err.printStackTrace(System.err)).map(_ => req))
      }.map {
        result =>
          Stream.emit(result)
      }

    case req@ParametersAt(notebook, id, pos, _) =>
      router.getKernel(notebook).fold(IO.pure(req)) {
        kernel => kernel.parametersAt(id, pos).map {
          result => req.copy(signatures = result)
        }.handleError(err => req)
      }.map(Stream.emit)

    case KernelStatus(path, _) =>
      router.getKernel(path) match {
        case Some(kernel) =>
          IO.pure(kernelStatusInfo(path, kernel))
        case None =>
          IO.pure(Stream.emit(KernelStatus(path, KernelBusyState(busy = false, alive = false))))
      }

    case SetCellLanguage(path, id, language) =>
      for {
        _ <- getOrLoadNotebook(path)
        _ <- updateNotebook(path)(_.updateCell(id)(_.copy(language = language)))
      } yield Stream.empty

    case StartKernel(path, StartKernel.NoRestart) =>
      for {
        _        <- getOrLoadNotebook(path)
        notebook <- IO.fromEither(getNotebookRef(path))
        kernel   <- getKernel(notebook, oq)
      } yield kernelStatusInfo(path, kernel)

    case StartKernel(path, StartKernel.WarmRestart) => ??? // TODO
    case StartKernel(path, StartKernel.ColdRestart) =>
      for {
        notebook    <- getOrLoadNotebook(path)
        notebookRef <- IO.fromEither(getNotebookRef(path))
        restarted   <- forceRestartKernel(notebook, oq).getOrElse(getKernel(notebookRef, oq))
      } yield kernelStatusInfo(path, restarted)

    case StartKernel(path, StartKernel.Kill) =>
      router.shutdownKernel(path).map(_ => Stream.empty)


    case other =>
      IO.pure(Stream.empty)
  }

  def badMsgErr(msg: WebSocketFrame) = Error(1, new RuntimeException(s"Received bad message frame ${truncate(msg.toString, 32)}"))

  def truncate(str: String, len: Int): String = if (str.length < len) str else str.substring(0, len - 4) + "..."

}
