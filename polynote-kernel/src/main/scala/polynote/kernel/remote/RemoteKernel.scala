package polynote.kernel
package remote

import java.net.{InetAddress, InetSocketAddress}
import java.nio.channels.ClosedChannelException
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

import scala.compat.java8.FunctionConverters.enrichAsJavaFunction
import cats.effect.concurrent.{Deferred, Ref}
import cats.instances.list._
import cats.syntax.traverse._
import fs2.concurrent.{Queue, SignallingRef, Topic}
import fs2.{Chunk, Pipe, Stream}
import polynote.config.PolynoteConfig
import polynote.kernel.Kernel.Factory
import polynote.kernel.environment.{Config, CurrentNotebook, Env, NotebookUpdates, PublishResult, PublishStatus}
import polynote.kernel.interpreter.Interpreter
import polynote.kernel.logging.Logging
import polynote.kernel.remote
import polynote.kernel.remote.RemoteKernelClient.KernelEnvironment
import polynote.kernel.util.Publish
import polynote.messages._
import polynote.runtime.{StreamingDataRepr, TableOp}
import zio.{FiberRef, Promise, Task, RIO, UIO, ZIO}
import zio.blocking.{Blocking, effectBlocking}
import zio.clock.Clock
import zio.duration.Duration
import zio.interop.catz._
import zio.system.System

import scala.collection.JavaConverters._
import scala.concurrent.TimeoutException

class RemoteKernel[ServerAddress](
  transport: TransportServer[ServerAddress],
  updates: Stream[Task, NotebookUpdate],
  closed: Promise[Throwable, Unit]
) extends Kernel {

  private val requestId = new AtomicInteger(0)
  private def nextReq = requestId.getAndIncrement()

  private case class RequestHandler[R, T](handler: PartialFunction[RemoteRequestResponse, RIO[R, T]], promise: Promise[Throwable, T], env: R) {
    def run(rep: RemoteRequestResponse): UIO[Unit] = handler match {
      case handler if handler isDefinedAt rep => handler(rep).provide(env).to(promise).ignore.unit
      case _ => ZIO.unit
    }
  }
  private val waiting = new ConcurrentHashMap[Int, RequestHandler[_, _]]()

  private def wait[R, T](reqId: Int)(fn: PartialFunction[RemoteRequestResponse, RIO[R, T]]): RIO[R, Task[T]] = for {
    promise <- Promise.make[Throwable, T]
    env     <- ZIO.access[R](identity)
    _       <- ZIO(waiting.put(reqId, RequestHandler(fn, promise, env)))
  } yield promise.await

  private def done[T](reqId: Int, t: T): UIO[T] = ZIO.effectTotal(waiting.remove(reqId)).as(t)

  private def request[R <: BaseEnv, T](req: RemoteRequest)(fn: PartialFunction[RemoteRequestResponse, RIO[R, T]]): RIO[R, T] = closed.isDone.flatMap {
    case false =>
      for {
        promise <- Promise.make[Throwable, T]
        env     <- ZIO.access[R](identity)
        _       <- ZIO(waiting.put(req.reqId, RequestHandler(fn, promise, env)))
        _       <- transport.sendRequest(req)
        result  <- promise.await
      } yield result
    case true => ZIO.fail(new ClosedChannelException())
  }

  private def withHandler(rep: RemoteRequestResponse)(fn: RequestHandler[_, _] => Task[Unit]): Task[Unit] = Option(waiting.get(rep.reqId)) match {
    case Some(rh@RequestHandler(handler, promise, env)) => fn(rh)
    case _ => ZIO.unit
  }

  private val processResponses = transport.responses.evalMap[TaskC, Unit] {
    case KernelStatusResponse(status)    => PublishStatus(status)
    case rep@ResultResponse(_, result)   => withHandler(rep)(handler => PublishResult(result).provide(handler.env.asInstanceOf[PublishResult]))
    case rep@ResultsResponse(_, results) => withHandler(rep)(handler => PublishResult(results).provide(handler.env.asInstanceOf[PublishResult]))
    case rep: RemoteRequestResponse      => withHandler(rep)(_.run(rep))
  }.onFinalize(PublishStatus(KernelBusyState(busy = false, alive = false)))

  def init(): RIO[BaseEnv with GlobalEnv with CellEnv, Unit] = for {                                                           // have to start pulling the updates before getting the current notebook
    pullUpdates    <- updates.evalMap(transport.sendNotebookUpdate).interruptWhen(closed.await.either).compile.drain.fork        // otherwise some may be missed
    versioned      <- CurrentNotebook.getVersioned
    (ver, notebook) = versioned
    config         <- Config.access
    process        <- processResponses.compile.drain.fork
    startup        <- request(StartupRequest(nextReq, notebook, ver, config)) {
      case Announce(reqId, remoteAddress) => done(reqId, ()) // TODO: may want to keep the remote address to try reconnecting?
    }
  } yield ()

  def queueCell(id: CellID): RIO[BaseEnv with GlobalEnv with CellEnv, Task[Unit]] = {
    val reqId = nextReq
    request(QueueCellRequest(reqId, id)) {
      case UnitResponse(_) => wait(reqId) {
        case RunCompleteResponse(`reqId`) =>
          done(reqId, ())
      }
      case RunCompleteResponse(`reqId`) => wait(reqId) {
        case UnitResponse(_) =>
          done(reqId, ())
      }
    }
  }

  override def cancelAll(): RIO[BaseEnv with TaskManager, Unit] = request(CancelAllRequest(nextReq)) {
    case UnitResponse(reqId) => done(reqId, ())
  }

  def completionsAt(id: CellID, pos: Int): RIO[BaseEnv with GlobalEnv with CellEnv, List[Completion]] =
    request(CompletionsAtRequest(nextReq, id, pos)) {
      case CompletionsAtResponse(reqId, result) => done(reqId, result)
    }

  def parametersAt(id: CellID, pos: Int): RIO[BaseEnv with GlobalEnv with CellEnv, Option[Signatures]] =
    request(ParametersAtRequest(nextReq, id, pos)) {
      case ParametersAtResponse(reqId, result) => done(reqId, result)
    }

  def shutdown(): TaskB[Unit] = request(ShutdownRequest(nextReq)) {
    case ShutdownResponse(reqId) => done(reqId, ())
  }.timeout(Duration(10, TimeUnit.SECONDS)).flatMap {
    case Some(()) => ZIO.succeed(())
    case None =>
      Logging.warn("Waited for remote kernel to shut down for 10 seconds; killing the process")
  }.ensuring(close().orDie)

  def status(): TaskB[KernelBusyState] = request(StatusRequest(nextReq)) {
    case StatusResponse(reqId, status) => done(reqId, status)
  }

  def values(): TaskB[List[ResultValue]] = request(ValuesRequest(nextReq)) {
    case ValuesResponse(reqId, values) => done(reqId, values)
  }

  def getHandleData(handleType: HandleType, handle: Int, count: Int): RIO[BaseEnv with StreamingHandles, Array[ByteVector32]] =
    ZIO.access[StreamingHandles](_.sessionID).flatMap {
      sessionID => request(GetHandleDataRequest(nextReq, sessionID, handleType, handle, count)) {
        case GetHandleDataResponse(reqId, data) => done(reqId, data)
      }
    }

  def modifyStream(handleId: Int, ops: List[TableOp]): RIO[BaseEnv with StreamingHandles, Option[StreamingDataRepr]] =
    ZIO.access[StreamingHandles](_.sessionID).flatMap {
      sessionID => request(ModifyStreamRequest(nextReq, sessionID, handleId, ops)) {
        case ModifyStreamResponse(reqId, result) => done(reqId, result)
      }
    }

  def releaseHandle(handleType: HandleType, handleId: Int): RIO[BaseEnv with StreamingHandles, Unit] =
    ZIO.access[StreamingHandles](_.sessionID).flatMap {
      sessionID => request(ReleaseHandleRequest(nextReq, sessionID, handleType, handleId)) {
        case UnitResponse(reqId) => done(reqId, ())
      }
    }

  override def info(): TaskG[KernelInfo] = request(KernelInfoRequest(nextReq)) {
    case KernelInfoResponse(reqId, info) => done(reqId, info)
  }

  private def close(): TaskB[Unit] = for {
    _ <- closed.succeed(())
    _ <- waiting.values().asScala.toList.map(_.promise.interrupt).sequence
    _ <- ZIO.effectTotal(waiting.clear())
    _ <- transport.close()
  } yield ()

}

object RemoteKernel extends Kernel.Factory.Service {
  def apply[ServerAddress](transport: Transport[ServerAddress]): RIO[BaseEnv with GlobalEnv with CellEnv with NotebookUpdates, RemoteKernel[ServerAddress]] = for {
    server  <- transport.serve()
    updates <- NotebookUpdates.access
    closed  <- Promise.make[Throwable, Unit]
  } yield new RemoteKernel(server, updates, closed)

  override def apply(): RIO[BaseEnv with GlobalEnv with CellEnv with NotebookUpdates, Kernel] = apply(
    new SocketTransport(
      new SocketTransport.DeploySubprocess(
        new SocketTransport.DeploySubprocess.DeployJava[LocalKernelFactory]),
      Some("127.0.0.1")))
}


class RemoteKernelClient(
  kernel: Kernel,
  requests: Stream[TaskB, RemoteRequest],
  publishResponse: Publish[Task, RemoteResponse],
  close: TaskB[Unit],
  private[remote] val notebookRef: SignallingRef[Task, (Int, Notebook)] // for testing
) {

  private val sessionHandles = new ConcurrentHashMap[Int, BaseEnv with StreamingHandles]()

  def run(): RIO[KernelEnvironment, Int] =
    requests
      .map(handleRequest)
      .map(Stream.eval)
      .parJoinUnbounded
      .terminateAfter(_.isInstanceOf[ShutdownResponse])
      .evalMap(publishResponse.publish1)
      .compile.drain.as(0) <* close

  private def handleRequest(req: RemoteRequest): RIO[KernelEnvironment, RemoteResponse] = ZIO.access[KernelEnvironment](identity).flatMap {
    env =>
      val response = req match {
        case QueueCellRequest(reqId, cellID)              => kernel.queueCell(cellID).flatMap {
          completed =>
            completed
              .catchAll(err => publishResponse.publish1(ResultResponse(reqId, ErrorResult(err))))
              .ensuring(publishResponse.publish1(RunCompleteResponse(reqId)).orDie)
              .supervised
              .fork
        }.as(UnitResponse(reqId))
        case CancelAllRequest(reqId)                      => kernel.cancelAll().as(UnitResponse(reqId))
        case CompletionsAtRequest(reqId, cellID, pos)     => kernel.completionsAt(cellID, pos).map(CompletionsAtResponse(reqId, _))
        case ParametersAtRequest(reqId, cellID, pos)      => kernel.parametersAt(cellID, pos).map(ParametersAtResponse(reqId, _))
        case ShutdownRequest(reqId)                       => kernel.shutdown().as(ShutdownResponse(reqId))
        case StatusRequest(reqId)                         => kernel.status().map(StatusResponse(reqId, _))
        case ValuesRequest(reqId)                         => kernel.values().map(ValuesResponse(reqId, _))
        case GetHandleDataRequest(reqId, sid, ht, hid, c) => kernel.getHandleData(ht, hid, c).map(GetHandleDataResponse(reqId, _)).provideSomeM(streamingHandles(sid))
        case ModifyStreamRequest(reqId, sid, hid, ops)    => kernel.modifyStream(hid, ops).map(ModifyStreamResponse(reqId, _)).provideSomeM(streamingHandles(sid))
        case ReleaseHandleRequest(reqId, sid,  ht, hid)   => kernel.releaseHandle(ht, hid).as(UnitResponse(reqId)).provideSomeM(streamingHandles(sid))
        case KernelInfoRequest(reqId)                     => kernel.info().map(KernelInfoResponse(reqId, _))
        // TODO: Kernel needs an API to release all streaming handles (then we could let go of elements from sessionHandles map; right now they will just accumulate forever)
        case req => ZIO.succeed(UnitResponse(req.reqId))
      }

      response.provide(env.withReqId(req.reqId))
  }

  private def streamingHandles(sessionId: Int): RIO[BaseEnv, BaseEnv with StreamingHandles] =
    ZIO.access[BaseEnv](identity).flatMap {
      baseEnv =>
        Option(sessionHandles.get(sessionId)) match {
          case Some(env) => ZIO.succeed(env)
          case None => StreamingHandles.make(sessionId).map {
            handles => synchronized {
              Option(sessionHandles.get(sessionId)) match {
                case Some(env) => env
                case None =>
                  val env = Env.enrichWith[BaseEnv, StreamingHandles](baseEnv, handles)
                  sessionHandles.put(sessionId, env)
                  env
              }
            }
          }
        }
    }
  
}

object RemoteKernelClient extends polynote.app.App {

  override def run(args: List[String]): ZIO[Environment, Nothing, Int] =
    (parseArgs(args) >>= runThrowable).orDie

  def runThrowable(args: Args): RIO[Environment, Int] = tapRunThrowable(args, None)

  // for testing – so we can get out the remote kernel client
  def tapRunThrowable(args: Args, tapClient: Option[zio.Ref[RemoteKernelClient]]): RIO[Environment, Int] = for {
    addr            <- args.getSocketAddress
    kernelFactory   <- args.getKernelFactory
    transport       <- SocketTransport.connectClient(addr)
    requests         = transport.requests
    publishResponse  = Publish.fn[Task, RemoteResponse](rep => transport.sendResponse(rep).provide(Environment))
    localAddress    <- effectBlocking(InetAddress.getLocalHost.getHostAddress)
    firstRequest    <- requests.head.compile.lastOrError
    initial         <- firstRequest match {
      case req@StartupRequest(_, _, _, _) => ZIO.succeed(req)
      case other                          => ZIO.fail(new RuntimeException(s"Handshake error; expected StartupRequest but found ${other.getClass.getName}"))
    }
    notebookRef     <- SignallingRef[Task, (Int, Notebook)](initial.globalVersion -> initial.notebook)
    processUpdates  <- transport.updates.dropWhile(_.globalVersion <= initial.globalVersion)
      .evalMap(update => notebookRef.update { case (ver, notebook) => update.globalVersion -> update.applyTo(notebook) })
      .compile.drain
      .fork
    interpFactories <- interpreter.Loader.load
    kernelEnv       <- mkEnv(notebookRef, firstRequest.reqId, publishResponse, interpFactories, kernelFactory, initial.config)
    kernel          <- kernelFactory.apply().provide(kernelEnv)
    closed          <- Deferred[Task, Unit]
    client           = new RemoteKernelClient(kernel, requests, publishResponse, transport.close(), notebookRef)
    _               <- tapClient.fold(ZIO.unit)(_.set(client))
    _               <- kernel.init().provide(kernelEnv)
    _               <- publishResponse.publish1(Announce(initial.reqId, localAddress))
    exitCode        <- client.run().provide(kernelEnv)
    _               <- processUpdates.interrupt
  } yield exitCode

  def mkEnv(
    currentNotebook: SignallingRef[Task, (Int, Notebook)],
    reqId: Int,
    publishResponse: Publish[Task, RemoteResponse],
    interpreterFactories: Map[String, List[Interpreter.Factory]],
    kernelFactory: Factory.Service,
    polynoteConfig: PolynoteConfig
  ): Task[KernelEnvironment] = {
    val publishStatus = publishResponse.contramap(KernelStatusResponse.apply)
    TaskManager(publishStatus).map {
      taskManager => KernelEnvironment(
        currentNotebook,
        reqId,
        publishResponse,
        interpreterFactories,
        kernelFactory: Factory.Service,
        polynoteConfig: PolynoteConfig,
        taskManager,
        publishStatus
      )
    }
  }

  case class Args(address: Option[String] = None, port: Option[Int] = None, kernelFactory: Option[Kernel.Factory.LocalService] = None) {
    def getSocketAddress: Task[InetSocketAddress] = for {
      address       <- ZIO.fromOption(address).mapError(_ => new IllegalArgumentException("Missing required argument address"))
      port          <- ZIO.fromOption(port).mapError(_ => new IllegalArgumentException("Missing required argument port"))
      socketAddress <- ZIO(new InetSocketAddress(address, port))
    } yield socketAddress

    def getKernelFactory: Task[Kernel.Factory.LocalService] = ZIO.succeed(kernelFactory.getOrElse(LocalKernel))
  }

  private def parseArgs(args: List[String], current: Args = Args(), sideEffects: ZIO[Logging, Nothing, Unit] = ZIO.unit): RIO[Logging, Args] =
    args match {
      case Nil => sideEffects *> ZIO.succeed(current)
      case "--address" :: address :: rest => parseArgs(rest, current.copy(address = Some(address)))
      case "--port" :: port :: rest => parseArgs(rest, current.copy(port = Some(port.toInt)))
      case "--kernelFactory" :: factory :: rest => parseKernelFactory(factory).flatMap(factory => parseArgs(rest, current.copy(kernelFactory = Some(factory))))
      case other :: rest => parseArgs(rest, sideEffects = sideEffects *> Logging.warn(s"Ignoring unknown argument $other"))
    }

  private def parseKernelFactory(str: String) = for {
    factoryClass <- ZIO(Class.forName(str).asSubclass(classOf[Kernel.Factory.LocalService]))
    factoryInst  <- ZIO(factoryClass.newInstance())
  } yield factoryInst

  case class KernelEnvironment(
    currentNotebook: Ref[Task, (Int, Notebook)],
    reqId: Int,
    publishResponse: Publish[Task, RemoteResponse],
    interpreterFactories: Map[String, List[Interpreter.Factory]],
    kernelFactory: Factory.Service,
    polynoteConfig: PolynoteConfig,
    taskManager: TaskManager.Service,
    publishStatus: Publish[Task, KernelStatusUpdate]
  ) extends BaseEnvT with GlobalEnvT with CellEnvT {
    override val blocking: Blocking.Service[Any] = Environment.blocking
    override val clock: Clock.Service[Any] = Environment.clock
    override val logging: Logging.Service = Environment.logging
    override val system: System.Service[Any] = Environment.system

    override val publishResult: Publish[Task, Result] = new Publish[Task, Result] {
      override def publish1(result: Result): Task[Unit] = publishResponse.publish1(ResultResponse(reqId, result))
      override def publish: Pipe[Task, Result, Unit] = results => results.mapChunks {
        resultChunk => Chunk.singleton(ResultsResponse(reqId, ShortList(resultChunk.toList)))
      }.through(publishResponse.publish)
    }

    def withReqId(reqId: Int): KernelEnvironment = copy(reqId = reqId)
  }
}