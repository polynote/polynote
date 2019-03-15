package polynote.kernel.remote

import java.io.File
import java.net.{InetAddress, InetSocketAddress}
import java.nio.channels.{AsynchronousChannelGroup, Channels, SocketChannel}
import java.util.concurrent.{ExecutorService, Executors}

import cats.effect.concurrent.Ref
import cats.effect.{ContextShift, ExitCode, IO, IOApp}
import cats.syntax.apply._
import cats.syntax.either._
import cats.syntax.functor._
import fs2.{Chunk, Pipe, Stream}
import fs2.concurrent.{Enqueue, Queue, Topic}
import fs2.io.tcp.Socket
import polynote.config.{DependencyConfigs, PolynoteConfig}
import polynote.kernel.dependency.CoursierFetcher
import polynote.kernel.util.{KernelContext, Publish, ReadySignal}
import Publish.enqueueToPublish
import polynote.kernel.{KernelAPI, KernelBusyState, KernelStatusUpdate, SparkPolyKernel}
import polynote.messages.{KernelStatus, Message, Notebook, NotebookConfig, NotebookUpdate, ShortList, ShortString, Streaming}
import polynote.server.{SparkKernelFactory, SparkKernelLaunching, StreamingHandleManager}
import polynote.server.repository.NotebookRepository
import polynote.server.repository.ipynb.IPythonNotebookRepository
import scodec.Codec
import scodec.stream.decode
import scodec.stream.encode

import scala.concurrent.ExecutionContext

/**
  * This is the main class instantiated in a remote Spark situation; it starts the actual SparkPolyKernel and connects
  * back to the RemoteSparkKernel server.
  */
class RemoteSparkKernelClient(
  remoteAddress: InetSocketAddress
) extends Serializable with SparkKernelLaunching {

  private val executorService: ExecutorService = Executors.newCachedThreadPool()
  private val executionContext: ExecutionContext = ExecutionContext.fromExecutorService(executorService)
  protected implicit val contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.fromExecutorService(executorService))
//  private implicit val channelGroup: AsynchronousChannelGroup = AsynchronousChannelGroup.withCachedThreadPool(executorService, 1)

  private val myAddress = InetAddress.getLocalHost.getHostAddress
  private def newChannel: IO[SocketChannel] = IO(java.nio.channels.SocketChannel.open(remoteAddress))

  private val singleRequest = decode.once(RemoteRequest.codec)
  private val requestStream = decode.many(RemoteRequest.codec)

  private val streams = new StreamingHandleManager

  private val shutdownSignal: ReadySignal = ReadySignal()

  private def readInitialNotebook(channel: SocketChannel): IO[InitialNotebook] = for {
    messageOpt <- singleRequest.decodeChannel[IO](channel).compile.last
    message    <- IO.fromEither(Either.fromOption(messageOpt, new NoSuchElementException("Notebook not received from server")))
    notebook   <- message match {
      case nb @ InitialNotebook(_, _) => IO.pure(nb)
      case other => IO.raiseError(new IllegalStateException(s"Initial message was ${other.getClass.getSimpleName} rather than InitialNotebook"))
    }
  } yield notebook

  private def configFromNotebookConfig(notebookConfig: NotebookConfig): PolynoteConfig = notebookConfig match {
    case NotebookConfig(dependencies, exclusions, repositories, spark) =>
      PolynoteConfig(
        repositories = repositories.getOrElse(Nil),
        dependencies = dependencies.map(_.asInstanceOf[Map[String, List[String]]]).getOrElse(Map.empty),
        exclusions = exclusions.getOrElse(Nil),
        spark = spark.getOrElse(Map.empty)
      )
  }

  private def incomingRequests(channel: SocketChannel): Stream[IO, RemoteRequest] = requestStream.decodeChannel[IO](channel)

  private def send(channel: SocketChannel, response: RemoteResponse): IO[Unit] = for {
    bytes <- IO.fromEither(RemoteResponse.codec.encode(response).toEither.leftMap(err => new RuntimeException(err.message)))
    _     <- IO(channel.write(bytes.toByteBuffer))
  } yield ()

  private def sendAll(channel: SocketChannel): Pipe[IO, RemoteResponse, Unit] = _.evalMap {
    response => send(channel, response)
  }

  private def handleRequests(kernel: KernelAPI[IO], notebookRef: Ref[IO, Notebook]): Pipe[IO, RemoteRequest, RemoteResponse] = _.map {
    case InitialNotebook(reqId, notebook) => Stream.eval(notebookRef.set(notebook).as(UnitResponse(reqId)))
    case Shutdown(reqId) => Stream.eval(kernel.shutdown().as(UnitResponse(reqId))) ++ Stream.eval(shutdownSignal.complete).drain
    case StartInterpreterFor(reqId, cell) => Stream.eval(kernel.startInterpreterFor(cell).as(UnitResponse(reqId)))
    case QueueCell(reqId, cell) => Stream.eval(kernel.queueCell(cell)).flatMap {
      ioResultStream => Stream.emit(CellQueued(reqId)) ++
        Stream.eval(ioResultStream).flatMap {
          resultStream =>
            Stream.emit(StreamStarted(reqId)) ++
              resultStream.mapChunks {
                resultChunk => Chunk(ResultStreamElements(reqId, ShortList(resultChunk.toList)))
              } ++ Stream.emit(StreamEnded(reqId))
        }
    }
    case CompletionsAt(reqId, cell, pos) => Stream.eval(kernel.completionsAt(cell, pos).map(CompletionsResponse(reqId, _)))
    case ParametersAt(reqId, cell, pos) => Stream.eval(kernel.parametersAt(cell, pos).map(ParameterHintsResponse(reqId, _)))
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
    case UpdateNotebookRequest(reqId, update) => Stream.eval(notebookRef.update(update.applyTo).as(UnitResponse(reqId)))
  }.parJoinUnbounded


  def run(): IO[ExitCode] = for {
    channel        <- newChannel
    outputMessages <- Queue.unbounded[IO, RemoteResponse]
    outputWriter   <- outputMessages.dequeue.through(sendAll(channel)).compile.drain.start
    _              <- outputMessages.enqueue1(Announce(myAddress))
    notebookReq    <- readInitialNotebook(channel)
    notebook        = notebookReq.notebook
    _              <- send(channel, UnitResponse(notebookReq.reqId))
    nbConfig        = notebook.config.getOrElse(NotebookConfig.empty)
    notebookRef    <- Ref[IO].of(notebook)
    conf            = configFromNotebookConfig(nbConfig)
    statusUpdates   = outputMessages.contramap[KernelStatusUpdate](update => MessageResponse(KernelStatus(notebook.path, update)))
    kernel         <- kernelFactory(conf).launchKernel(notebookRef.get _, statusUpdates)
    incoming        = incomingRequests(channel)
    _              <- incoming.through(handleRequests(kernel, notebookRef)).compile.drain
  } yield ExitCode.Success

}
