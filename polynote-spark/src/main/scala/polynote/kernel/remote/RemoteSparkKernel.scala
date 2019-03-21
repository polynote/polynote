package polynote.kernel.remote

import java.net.{InetSocketAddress, URI, URISyntaxException}
import java.nio.ByteBuffer
import java.nio.channels._
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.concurrent.{ConcurrentHashMap, ExecutorService, Executors, LinkedBlockingQueue}

import cats.effect.concurrent.{Deferred, Semaphore}
import cats.effect.{ContextShift, Fiber, IO, Timer}
import cats.syntax.apply._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import cats.instances.list._
import fs2.{Chunk, Stream}
import fs2.concurrent.Queue
import org.log4s.getLogger
import polynote.config.PolynoteConfig
import polynote.kernel._
import polynote.kernel.util.{Publish, ReadySignal}
import polynote.messages.{ByteVector32, CellID, CellResult, HandleType, Lazy, Notebook, NotebookUpdate, ShortString, Streaming, Updating}
import polynote.runtime._
import polynote.util.Memoize

import scala.concurrent.duration.{Duration, MINUTES}
import scala.collection.JavaConverters._
import scala.concurrent.CancellationException

class RemoteSparkKernel(
  val statusUpdates: Publish[IO, KernelStatusUpdate],
  getNotebook: () => IO[Notebook],
  config: PolynoteConfig,
  transport: TransportServer)(implicit
  contextShift: ContextShift[IO]
) extends KernelAPI[IO] {

  private val remoteAddr = Deferred.unsafe[IO, String]
  private val mainFiber = Deferred.unsafe[IO, Fiber[IO, Unit]]
  private val shutdownSignal = ReadySignal()

  private val requestId = new AtomicInteger(0)
  private val requests = new ConcurrentHashMap[Int, Deferred[IO, Either[Throwable, RemoteResponse]]]()
  private val streamRequests = new ConcurrentHashMap[Int, Deferred[IO, Queue[IO, Option[Result]]]]

  private val logger = getLogger

  /**
    * Send the given message, expecting the given response, and return an IO representing the response being received.
    * The outer IO of the return value sends the request, and the inner layer awaits the response.
    */
  private def send1[Rep <: RemoteResponse](req: RemoteRequest): IO[IO[Rep]] = for {
    promise <- Deferred[IO, Either[Throwable, Rep]]
    _       <- IO(requests.put(req.reqId, promise.asInstanceOf[Deferred[IO, Either[Throwable, RemoteResponse]]]))
    _       <- transport.sendRequest(req)
  } yield promise.get.flatMap(IO.fromEither).guarantee(IO { requests.remove(req.reqId); () })

  /**
    * Construct the given message with a fresh request ID, and send it, expecting the given response type. Returns an IO
    * which awaits the response.
    */
  private def request1[Rep <: RemoteResponse](mkReq: Int => RemoteRequest): IO[Rep] = for {
    id      <- IO(requestId.getAndIncrement())
    req      = mkReq(id)
    result  <- send1[Rep](req).flatten
  } yield result

  /**
    * Prepare to receive streaming results. The outer IO does the preparation, and the inner IO waits for the
    * stream to start. The provided message must be sent separately in between evaluating the outer and inner IO!
    */
  private def prepareResultStream(req: RemoteRequest): IO[IO[Stream[IO, Result]]] = for {
    queuePromise <- Deferred[IO, Queue[IO, Option[Result]]]
    _            <- IO(streamRequests.put(req.reqId, queuePromise))
  } yield queuePromise.get.map(q => q.dequeue.unNoneTerminate.map(transformResult).onFinalize(IO { streamRequests.remove(req.reqId); () }))

  private def handleOneResponse(rep: RequestResponse) = Option(requests.get(rep.reqId)) match {
    case None => IO(logger.error(s"Received response $rep, but there is no corresponding request outstanding"))
    case Some(deferred) => deferred.complete(Right(rep))
  }

  private def handleStreamStart(reqId: Int) = Option(streamRequests.get(reqId)) match {
    case None => IO(logger.error(s"Received stream start for $reqId, but there is no corresponding stream request outstanding"))
    case Some(deferred) =>
      Queue.unbounded[IO, Option[Result]] >>= deferred.complete
  }

  private def withQueue(reqId: Int, fn: Queue[IO, Option[Result]] => IO[Unit]) = Option(streamRequests.get(reqId)) match {
    case None => IO(logger.error(s"Received stream start for $reqId, but there is no corresponding stream request outstanding"))
    case Some(deferred) => deferred.get >>= fn
  }

  private def transformResult(result: Result): Result = result match {
    case rv @ ResultValue(_, _, reprs, _, _, _) =>
      // the ResultValue will be missing its JVM value and its type. Also, any handle-based reprs won't refer to handles
      // within this JVM. We shouldn't need the value or type outside of the actual kernel, but we do have to fix
      // the repr handles.
      rv.copy(reprs = reprs.map {
        case s @ StreamingDataRepr(_, _, _) => StreamingDataRepr.fromHandle(transformRepr(s))
        case l @ LazyDataRepr(_, _) => LazyDataRepr.fromHandle(transformRepr(l))
        case u @ UpdatingDataRepr(remoteHandle, dataType) =>
          val local = UpdatingDataRepr(dataType)
          updatingHandleMapping.put(remoteHandle, local.handle)
          local
        case repr => repr
      })
    case _ => result
  }

  private def consumeResponses(responses: Stream[IO, RemoteResponse]) = responses.map {
    case Announce(remoteAddress) =>
      Stream.eval(getNotebook().flatMap(notebook => request1[UnitResponse](InitialNotebook(_, notebook)).as(()))) ++
        Stream.eval(remoteAddr.complete(remoteAddress).handleErrorWith(_ => IO.unit))
    case KernelStatusResponse(update) => Stream.eval(statusUpdates.publish1(update))
    case StreamStarted(reqId) => Stream.eval(handleStreamStart(reqId))
    case StreamEnded(reqId) => Stream.eval(withQueue(reqId, _.enqueue1(None)))
    case ResultStreamElements(reqId, elements) => Stream.eval(withQueue(reqId, q => Stream.emits(elements).map(Some(_)).through(q.enqueue).compile.drain))
    case rep: RequestResponse => Stream.eval(handleOneResponse(rep))
  }.parJoinUnbounded

  private val initialize = Memoize.unsafe {
    for {
      _ <- consumeResponses(transport.responses.interruptWhen(shutdownSignal())).compile.drain.start
      _ <- IO(logger.info("Started processing responses"))
      _ <- transport.connected
      _ <- remoteAddr.get
    } yield ()
  }

  def init: IO[Unit] = initialize.get

  private def cancelRequests() = for {
    reqs <- IO(requests.values.asScala.toList)
    _    <- reqs.map(_.complete(Left(new CancellationException("Request cancelled")))).sequence
  } yield ()

  def shutdown(): IO[Unit] =
    initialize.tryCancel() *>
      (IO(logger.info("Shutting down remote kernel")) *>
        request1[UnitResponse](Shutdown(_)) *>
        IO(logger.info("Remote kernel notified of shutdown"))).guarantee(
          cancelRequests() *>
          shutdownSignal.complete *>
          transport.close() *>
          IO(logger.info("Kernel server stopped")))

  def startInterpreterFor(cell: CellID): IO[Stream[IO, Result]] = for {
    reqId    <- IO(requestId.getAndIncrement())
    req       = StartInterpreterFor(reqId, cell)
    ioStream <- prepareResultStream(req)
    _        <- transport.sendRequest(req)
    stream   <- ioStream
  } yield stream

  def runCell(cell: CellID): IO[Stream[IO, Result]] = queueCell(cell).flatten

  def queueCell(cell: CellID): IO[IO[Stream[IO, Result]]] = for {
    reqId    <- IO(requestId.getAndIncrement())
    req       = QueueCell(reqId, cell)
    ioStream <- prepareResultStream(req)
    queued   <- send1[CellQueued](req).flatten
  } yield ioStream

  // we want to queue all the cells, but evaluate them in order. So the outer IO of the result runs the outer IO of queueCell for all the cells.
  override def runCells(cells: List[CellID]): IO[Stream[IO, CellResult]] =
    getNotebook().flatMap {
      notebook =>
        cells.map {
          id => queueCell(id).map(_.map(_.map(result => CellResult(ShortString(notebook.path), id, result))))
        }.sequence.map {
          queued => Stream.emits(queued).flatMap(run => Stream.eval(run).flatten)
        }
    }

  def completionsAt(cell: CellID, pos: Int): IO[List[Completion]] =
    request1[CompletionsResponse](CompletionsAt(_, cell, pos)).map(_.completions)

  def parametersAt(cell: CellID, pos: Int): IO[Option[Signatures]] =
    request1[ParameterHintsResponse](ParametersAt(_, cell, pos)).map(_.signatures)

  def currentSymbols(): IO[List[ResultValue]] =
    request1[CurrentSymbolsResponse](CurrentSymbols(_)).map(_.symbols)

  def currentTasks(): IO[List[TaskInfo]] =
    request1[CurrentTasksResponse](CurrentTasks(_)).map(_.tasks)

  def idle(): IO[Boolean] =
    request1[IdleResponse](IdleRequest(_)).map(_.idle)

  def info: IO[Option[KernelInfo]] =
    request1[InfoResponse](InfoRequest(_)).map(_.info)

  private def getRemoteHandle(handleType: HandleType, handle: Int): IO[Int] = IO {
    handleType match {
      case Lazy =>
        LazyDataRepr.getHandle(handle).collect {
          case MappedLazyHandle(`handle`, _, remote) => remote
        }

      case Streaming =>
        StreamingDataRepr.getHandle(handle).collect {
          case MappedStreamingHandle(`handle`, _, _, remote) => remote
        }

      case Updating => Option(updatingHandleMapping.get(handle))
    }
  }.flatMap {
    opt => opt.fold(IO.raiseError[Int](new IllegalArgumentException("No corresponding remote handle exists")))(IO.pure)
  }

  def getHandleData(handleType: HandleType, handle: Int, count: Int): IO[Array[ByteVector32]] =
    getRemoteHandle(handleType, handle).flatMap {
      remoteHandle => request1[HandleDataResponse](GetHandleDataRequest(_, handleType, remoteHandle, count)).map(_.data)
    }


  def modifyStream(handleId: Int, ops: List[TableOp]): IO[Option[StreamingDataRepr]] =
    getRemoteHandle(Streaming, handleId).flatMap {
      remoteHandle => request1[ModifyStreamResponse](ModifyStreamRequest(_, remoteHandle, ops)).map(_.repr)
    }

  def releaseHandle(handleType: HandleType, handleId: Int): IO[Unit] =
    getRemoteHandle(handleType, handleId).flatMap {
      remoteHandle => request1[UnitResponse](ReleaseHandleRequest(_, handleType, remoteHandle)).as(())
    }


  def cancelTasks(): IO[Unit] =
    request1[UnitResponse](CancelTasksRequest(_)).as(())

  def updateNotebook(version: Int, update: NotebookUpdate): IO[Unit] =
    request1[UnitResponse](UpdateNotebookRequest(_, version, update)).as(())

  /**
    * Transform a repr from the remote kernel into local JVM space
    */
  private def transformRepr(repr: StreamingDataRepr): Int => MappedStreamingHandle =
    MappedStreamingHandle(_, repr.dataType, repr.knownSize, repr.handle)

  private def transformRepr(repr: LazyDataRepr): Int => MappedLazyHandle =
    MappedLazyHandle(_, repr.dataType, repr.handle)

  // mapping of remote to local handles
  private val updatingHandleMapping = new ConcurrentHashMap[Int, Int]()

  /**
    * An iterator that fetches data from the remote kernel stream handle
    */
  private class RemoteStreamIterator(remoteHandle: Int, batchSize: Int = 512, fetchAhead: Int = 4) extends Iterator[ByteBuffer] {
    private val fetchComplete = new AtomicBoolean(false)

    private def fetchNext(): IO[Unit] = request1[HandleDataResponse](GetHandleDataRequest(_, Streaming, remoteHandle, batchSize)).flatMap {
      case rep if rep.data.isEmpty => IO(fetchComplete.set(true))
      case rep =>
        if (chunkQueue.offer(rep.data)) {
          fetchNext()
        } else {
          IO(chunkQueue.put(rep.data)) *> fetchNext()
        }
    }

    private val chunkQueue = new LinkedBlockingQueue[Array[ByteVector32]](fetchAhead)
    @volatile private var currentIterator: Iterator[ByteBuffer] = _

    val fetching: Fiber[IO, Unit] = fetchNext().start.unsafeRunSync()

    def hasNext: Boolean =
      ((currentIterator != null && currentIterator.hasNext) || !chunkQueue.isEmpty) || !(fetchComplete.get() && chunkQueue.isEmpty)


    def next(): ByteBuffer = {
      if (currentIterator == null || !currentIterator.hasNext) {
        currentIterator = chunkQueue.take().iterator.map(_.toByteBuffer)
      }
      currentIterator.next()
    }
  }

  /**
    * A streaming handle implementation that proxies to the connected remote kernel
    */
  private case class MappedStreamingHandle(handle: Int, dataType: DataType, knownSize: Option[Int], remoteHandle: Int) extends StreamingDataRepr.Handle {
    def iterator: Iterator[ByteBuffer] = new RemoteStreamIterator(remoteHandle)

    def modify(ops: List[TableOp]): Either[Throwable, Int => StreamingDataRepr.Handle] =
      modifyStream(handle, ops).map(_.map(transformRepr).toRight(new UnsupportedOperationException("Stream does not support table operations")))
        .attempt
        .unsafeRunSync()    // no choice but to run this synchronously – the Handle API doesn't have cats-effect so can't express in IO
        .flatMap(identity)

    override def release(): Unit = {
      super.release()
      releaseHandle(Streaming, handle).attempt.unsafeRunAsyncAndForget()
    }
  }

  /**
    * A lazy handle implementation that proxies to the connected remote kernel
    */
  private case class MappedLazyHandle(handle: Int, dataType: DataType, remoteHandle: Int) extends LazyDataRepr.Handle {
    @volatile private var computedFlag: Boolean = false
    def isEvaluated: Boolean = computedFlag
    lazy val data: ByteBuffer = {
      computedFlag = true
      getHandleData(Lazy, remoteHandle, 1).unsafeRunSync().head.toByteBuffer
    }
  }

}

object RemoteSparkKernel {
  private val logger = org.log4s.getLogger

  private def taskInfo(msg: String, detail: String = "", status: TaskStatus = TaskStatus.Running, progress: Byte = 0) =
    UpdatedTasks(TaskInfo("kernel", msg, detail, status, progress) :: Nil)

  def apply[ServerAddress](
    statusUpdates: Publish[IO, KernelStatusUpdate],
    getNotebook: () => IO[Notebook],
    config: PolynoteConfig,
    transport: Transport[ServerAddress])(implicit
    contextShift: ContextShift[IO],
    timer: Timer[IO]
  ): IO[RemoteSparkKernel] = {
    def publish(msg: String, progress: Byte) = IO(logger.info(msg)) *> statusUpdates.publish1(taskInfo(msg, progress = progress))
    for {
      _        <- publish("Starting kernel process", 0)
      notebook <- getNotebook()
      server   <- transport.serve(config, notebook)
      kernel    = new RemoteSparkKernel(statusUpdates, getNotebook, config, server)
      start    <- kernel.init.start
      _        <- publish("Awaiting remote kernel", 64)
      _        <- server.connected
      _        <- publish("Remote client connected", 128.toByte)
      _        <- start.join.timeout(Duration(2, MINUTES)).handleErrorWith { err => start.cancel.flatMap(_ => kernel.shutdown().start) *> IO.raiseError(err) }
      _        <- publish("Remote kernel started", 255.toByte)
    } yield kernel
  }.handleErrorWith {
    err =>
      IO(logger.error(err)("Failed to connect to remote client"))
        statusUpdates.publish1(taskInfo("Failed to connect", err.getMessage, TaskStatus.Error)) *>
        IO.raiseError(err)
  }

}