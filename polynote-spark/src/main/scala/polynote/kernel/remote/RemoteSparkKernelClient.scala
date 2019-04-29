package polynote.kernel.remote

import java.net.{InetAddress, InetSocketAddress}

import cats.effect.concurrent.Ref
import cats.effect._
import cats.syntax.apply._
import cats.syntax.either._
import cats.syntax.functor._
import cats.syntax.traverse._
import cats.instances.list._
import fs2.{Chunk, INothing, Pipe, Stream}
import fs2.concurrent.{Queue, SignallingRef}
import polynote.config.PolynoteConfig
import polynote.kernel.util.{CellContext, NotebookContext, Publish, ReadySignal}
import Publish.enqueueToPublish
import polynote.kernel._
import polynote.messages.{CellID, DeleteCell, InsertCell, Notebook, NotebookCell, NotebookConfig, NotebookUpdate, ShortList, Streaming}
import polynote.runtime.{StreamingDataRepr, ValueRepr}
import polynote.server.{KernelFactory, KernelLaunching, SparkKernelFactory, StreamingHandleManager}

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext

/**
  * This is the main class instantiated in a remote Spark situation; it starts the actual SparkPolyKernel and connects
  * back to the RemoteSparkKernel server.
  */
class RemoteSparkKernelClient(
  transport: TransportClient,
  notebookContext: SignallingRef[IO, (NotebookContext, Option[NotebookUpdate])],
  kernelFactory: KernelFactory[IO])(implicit
  protected val executionContext: ExecutionContext,
  protected val contextShift: ContextShift[IO],
  protected val timer: Timer[IO]
) extends Serializable {

  private val logger = org.log4s.getLogger

  private val myAddress = InetAddress.getLocalHost.getHostAddress

  // we don't have to multiplex, since there's only one "subscriber" - the remote kernel proxy.
  // But the hosted kernel won't manage the streaming data on its own, since it requires the multiplexer in front.
  private val streams = new StreamingHandleManager

  private val shutdownSignal: ReadySignal = ReadySignal()
  private val started: ReadySignal = ReadySignal()

  private def readInitialNotebook(head: Stream[IO, RemoteRequest]): IO[InitialNotebook] = for {
    messageOpt  <- head.compile.last
    message     <- IO.fromEither(Either.fromOption(messageOpt, new NoSuchElementException("Notebook not received from server")))
    notebookReq <- message match {
      case nb @ InitialNotebook(_, notebook) =>
        if (notebook.cells.isEmpty) IO.pure(nb) else {
          notebook.cells.map {
            cell => CellContext(cell.id).flatMap(context => notebookContext.update {
              case (ctx, _) =>
                ctx.insertLast(context)
                (ctx, None)
            })
          }
        }.sequence.as(nb)
      case other => IO.raiseError(new IllegalStateException(s"Initial message was ${other.getClass.getSimpleName} rather than InitialNotebook"))
    }
  } yield notebookReq

  private def configFromNotebookConfig(notebookConfig: NotebookConfig): PolynoteConfig = notebookConfig match {
    case NotebookConfig(dependencies, exclusions, repositories, spark) =>
      PolynoteConfig(
        repositories = repositories.getOrElse(Nil),
        dependencies = dependencies.map(_.asInstanceOf[Map[String, List[String]]]).getOrElse(Map.empty),
        exclusions = exclusions.getOrElse(Nil),
        spark = spark.getOrElse(Map.empty)
      )
  }

  private def respondResultStream(reqId: Int, resultStream: Stream[IO, Result]) =
    Stream.emit(StreamStarted(reqId)) ++ resultStream.mapChunks {
      resultChunk => Chunk(ResultStreamElements(reqId, ShortList(resultChunk.toList)))
    } ++ Stream.emit(StreamEnded(reqId))

  private def shutdown(reqId: Int, kernel: KernelAPI[IO]) = for {
    _ <- IO(logger.info("Shutting down"))
    _ <- kernel.shutdown()
    _ <- transport.sendResponse(UnitResponse(reqId))
    _ <- IO(logger.info("Kernel shutdown complete; closing transport"))
    _ <- shutdownSignal.complete
    _ <- transport.close()
  } yield ()

  private def handleRequests(
    kernel: KernelAPI[IO],
    notebookRef: Ref[IO, Notebook]
  ): Pipe[IO, RemoteRequest, RemoteResponse] = _.map {
    case InitialNotebook(reqId, notebook) => Stream.eval(notebookRef.set(notebook).as(UnitResponse(reqId)))
    case Shutdown(reqId) =>
      Stream.eval(shutdown(reqId, kernel)).drain

    case StartInterpreterFor(reqId, cell) =>
      Stream.eval(notebookRef.get).map(_.cell(cell)).evalMap(cell => kernel.startInterpreterFor(cell)).flatMap(respondResultStream(reqId, _))

    case QueueCell(reqId, cell) => Stream.eval(notebookRef.get).map(_.cell(cell)).map(cell => kernel.queueCell(cell)).flatMap {
      ioResultStream =>
        Stream.emit(CellQueued(reqId)) ++ Stream.eval(ioResultStream).evalMap(identity).flatMap(respondResultStream(reqId, _))
    }
    case CompletionsAt(reqId, cell, pos) => Stream.eval(notebookRef.get).map(_.cell(cell)).evalMap(cell => kernel.completionsAt(cell, pos).map(CompletionsResponse(reqId, _)))
    case ParametersAt(reqId, cell, pos) => Stream.eval(notebookRef.get).map(_.cell(cell)).evalMap(cell => kernel.parametersAt(cell, pos).map(ParameterHintsResponse(reqId, _)))
    case CurrentSymbols(reqId) => Stream.eval(kernel.currentSymbols().map(CurrentSymbolsResponse(reqId, _)))
    case CurrentTasks(reqId) => Stream.eval(kernel.currentTasks().map(CurrentTasksResponse(reqId, _)))
    case IdleRequest(reqId) => Stream.eval(kernel.idle().map(IdleResponse(reqId, _)))
    case InfoRequest(reqId) => Stream.eval(kernel.info.map(InfoResponse(reqId, _)))
    case GetHandleDataRequest(reqId, handleType, handle, count) => handleType match {
      case Streaming => Stream.eval(streams.getStreamData(handle, count).map(HandleDataResponse(reqId, _)))
      case _ => Stream.eval(kernel.getHandleData(handleType, handle, count).map(HandleDataResponse(reqId, _)))
    }
    case ModifyStreamRequest(reqId, handleId, ops) => Stream.eval(kernel.modifyStream(handleId, ops).map(ModifyStreamResponse(reqId, _)))
    case ReleaseHandleRequest(reqId, handleType, handleId) => Stream.eval(
      (handleType match {
        case Streaming => streams.releaseStreamHandle(handleId)
        case _ => IO.unit
      }) *> kernel.releaseHandle(handleType, handleId).as(UnitResponse(reqId))
    )
    case CancelTasksRequest(reqId) => Stream.eval(kernel.cancelTasks().as(UnitResponse(reqId)))
    case UpdateNotebookRequest(reqId, update) => Stream.eval(notebookRef.update(update.applyTo).flatMap { _ =>
      update match {
        case InsertCell(_, _, _, cell, after) => CellContext(cell.id).flatMap {
          cellContext => notebookContext.update {
            case (nbCtx, _) =>
              nbCtx.insert(cellContext, Option(after))
              (nbCtx, None)
          }
        }
        case DeleteCell(_, _, _, id) => notebookContext.update {
          case (nbCtx, _) =>
            nbCtx.remove(id)
            (nbCtx, None)
        }
        case _ => IO.unit
      }
    }.as(UnitResponse(reqId)))
  }.parJoinUnbounded

  def run(): IO[ExitCode] = for {
    outputMessages <- Queue.unbounded[IO, RemoteResponse]
    outputWriter   <- outputMessages.dequeue.evalMap(transport.sendResponse).interruptWhen(shutdownSignal()).compile.drain.start
    _              <- IO(logger.info("Remote kernel client starting up"))
    _              <- outputMessages.enqueue1(Announce(myAddress))
    notebookReq    <- readInitialNotebook(transport.requests.head)
    _              <- IO(logger.info("Handshake complete"))
    notebook        = notebookReq.notebook
    nbConfig        = notebook.config.getOrElse(NotebookConfig.empty)
    notebookRef    <- Ref[IO].of(notebook)
    conf            = configFromNotebookConfig(nbConfig)
    statusUpdates   = outputMessages.contramap[KernelStatusUpdate](update => KernelStatusResponse(update))
    _              <- IO(logger.info("Launching kernel"))
    kernel         <- kernelFactory.launchKernel(notebookRef.get _, notebookContext, statusUpdates, conf)
    mainFiber      <- transport.requests.through(handleRequests(kernel, notebookRef)).through(outputMessages.enqueue).interruptWhen(shutdownSignal()).compile.drain.start
    _              <- Stream.repeatEval(kernel.idle()).takeWhile(_ == false).compile.drain // wait until kernel is idle
    _              <- IO(logger.info("Kernel ready"))
    _              <- outputMessages.enqueue1(UnitResponse(notebookReq.reqId))
    _              <- started.complete
    _              <- mainFiber.join
    _              <- IO(logger.info("Closing message loop"))
    _              <- outputWriter.join
    _              <- IO(logger.info("Kernel stopped"))
  } yield ExitCode.Success

  def shutdown(): IO[Unit] = shutdownSignal.complete
}

object RemoteSparkKernelClient extends IOApp with KernelLaunching {

  private val logger = org.log4s.getLogger

  import scala.concurrent.ExecutionContext.Implicits.global

  override protected def kernelFactory: KernelFactory[IO] = new SparkKernelFactory(dependencyFetchers)

  @tailrec
  private def getArgs(remaining: List[String]): IO[InetSocketAddress] = remaining match {
    case Nil => IO.raiseError(new IllegalArgumentException("Missing required argument --remoteAddress address:port"))
    case "--remoteAddress" :: address :: _ => parseHostPort(address)
    case _ :: rest => getArgs(rest)
  }

  def run(args: List[String]): IO[ExitCode] = for {
    address   <- getArgs(args)
    _         <- IO(logger.info(s"Will connect to $address"))
    transport <- new SocketTransport().connect(address)
    nbctx     <- SignallingRef[IO, (NotebookContext, Option[NotebookUpdate])]((new NotebookContext(), None))
    client    <- IO(new RemoteSparkKernelClient(transport, nbctx, kernelFactory))
    result    <- client.run()
  } yield result
}