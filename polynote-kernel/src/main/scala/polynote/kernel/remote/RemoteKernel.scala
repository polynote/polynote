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
import zio.{FiberRef, Promise, Task, TaskR, UIO, ZIO}
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

  private case class RequestHandler[R, T](handler: PartialFunction[RemoteRequestResponse, TaskR[R, T]], promise: Promise[Throwable, T], env: R) {
    def run(rep: RemoteRequestResponse): UIO[Unit] = handler match {
      case handler if handler isDefinedAt rep => handler(rep).provide(env).to(promise).ignore.unit
      case _ => ZIO.unit
    }
  }
  private val waiting = new ConcurrentHashMap[Int, RequestHandler[_, _]]()

  private def wait[R, T](reqId: Int)(fn: PartialFunction[RemoteRequestResponse, TaskR[R, T]]): TaskR[R, Task[T]] = for {
    promise <- Promise.make[Throwable, T]
    env     <- ZIO.access[R](identity)
    _       <- ZIO(waiting.put(reqId, RequestHandler(fn, promise, env)))
  } yield promise.await

  private def done[T](reqId: Int, t: T): UIO[T] = ZIO.effectTotal(waiting.remove(reqId)).const(t)

  private def request[R <: BaseEnv, T](req: RemoteRequest)(fn: PartialFunction[RemoteRequestResponse, TaskR[R, T]]): TaskR[R, T] = closed.isDone.flatMap {
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

  private val processRequests = transport.responses.evalMap[TaskC, Unit] {
    case KernelStatusResponse(status)    => PublishStatus(status)
    case rep@ResultResponse(_, result)   => withHandler(rep)(handler => PublishResult(result).provide(handler.env.asInstanceOf[PublishResult]))
    case rep@ResultsResponse(_, results) => withHandler(rep)(handler => PublishResult(results).provide(handler.env.asInstanceOf[PublishResult]))
    case rep: RemoteRequestResponse      => withHandler(rep)(_.run(rep))
  }.onFinalize(PublishStatus(KernelBusyState(busy = false, alive = false)))

  def init(): TaskR[BaseEnv with GlobalEnv with CellEnv, Unit] = for {
    update   <- updates.evalMap(update => transport.sendRequest(UpdateNotebookRequest(nextReq, update)))
        .interruptWhen(closed.await.either)
        .compile.drain.fork
    process  <- processRequests.compile.drain.fork
    notebook <- CurrentNotebook.get
    config   <- Config.access
    startup  <- request(StartupRequest(nextReq, notebook, config)) {
      case Announce(reqId, remoteAddress) => done(reqId, ()) // TODO: may want to keep the remote address to try reconnecting?
    }
  } yield ()

  def queueCell(id: CellID): TaskR[BaseEnv with GlobalEnv with CellEnv, Task[Unit]] = {
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

  override def cancelAll(): TaskR[BaseEnv with TaskManager, Unit] = request(CancelAllRequest(nextReq)) {
    case UnitResponse(reqId) => done(reqId, ())
  }

  def completionsAt(id: CellID, pos: Int): TaskR[BaseEnv with GlobalEnv with CellEnv, List[Completion]] =
    request(CompletionsAtRequest(nextReq, id, pos)) {
      case CompletionsAtResponse(reqId, result) => done(reqId, result)
    }

  def parametersAt(id: CellID, pos: Int): TaskR[BaseEnv with GlobalEnv with CellEnv, Option[Signatures]] =
    request(ParametersAtRequest(nextReq, id, pos)) {
      case ParametersAtResponse(reqId, result) => done(reqId, result)
    }

  def shutdown(): TaskB[Unit] = request(ShutdownRequest(nextReq)) {
    case ShutdownResponse(reqId) => done(reqId, ())
  }.timeout(Duration(2, TimeUnit.MINUTES)).flatMap {
    case Some(()) => ZIO.succeed(())
    case None => ZIO.fail(new TimeoutException("Waited for remote kernel to shut down for 2 minutes"))
  }.ensuring(close().orDie)

  def status(): TaskB[KernelBusyState] = request(StatusRequest(nextReq)) {
    case StatusResponse(reqId, status) => done(reqId, status)
  }

  def values(): TaskB[List[ResultValue]] = request(ValuesRequest(nextReq)) {
    case ValuesResponse(reqId, values) => done(reqId, values)
  }

  def getHandleData(handleType: HandleType, handle: Int, count: Int): TaskR[BaseEnv with StreamingHandles, Array[ByteVector32]] =
    ZIO.access[StreamingHandles](_.sessionID).flatMap {
      sessionID => request(GetHandleDataRequest(nextReq, sessionID, handleType, handle, count)) {
        case GetHandleDataResponse(reqId, data) => done(reqId, data)
      }
    }

  def modifyStream(handleId: Int, ops: List[TableOp]): TaskR[BaseEnv with StreamingHandles, Option[StreamingDataRepr]] =
    ZIO.access[StreamingHandles](_.sessionID).flatMap {
      sessionID => request(ModifyStreamRequest(nextReq, sessionID, handleId, ops)) {
        case ModifyStreamResponse(reqId, result) => done(reqId, result)
      }
    }

  def releaseHandle(handleType: HandleType, handleId: Int): TaskR[BaseEnv with StreamingHandles, Unit] =
    ZIO.access[StreamingHandles](_.sessionID).flatMap {
      sessionID => request(ReleaseHandleRequest(nextReq, sessionID, handleType, handleId)) {
        case UnitResponse(reqId) => done(reqId, ())
      }
    }

  private def close(): TaskB[Unit] = for {
    _ <- closed.succeed(())
    _ <- waiting.values().asScala.toList.map(_.promise.interrupt).sequence
    _ <- ZIO.effectTotal(waiting.clear())
    _ <- transport.close()
  } yield ()

}

object RemoteKernel extends Kernel.Factory.Service {
  def apply[ServerAddress](transport: Transport[ServerAddress]): TaskR[BaseEnv with GlobalEnv with CellEnv with NotebookUpdates, Kernel] = for {
    server  <- transport.serve()
    updates <- NotebookUpdates.access
    closed  <- Promise.make[Throwable, Unit]
  } yield new RemoteKernel(server, updates, closed)

  override def apply(): TaskR[BaseEnv with GlobalEnv with CellEnv with NotebookUpdates, Kernel] = apply(
    new SocketTransport(
      new SocketTransport.DeploySubprocess(
        new SocketTransport.DeploySubprocess.DeployJava[LocalKernelFactory]),
      Some("127.0.0.1")))
}


class RemoteKernelClient(
  kernel: Kernel,
  requests: Stream[TaskB, RemoteRequest],
  publishResponse: Publish[Task, RemoteResponse],
  closed: Deferred[Task, Unit]
) {

  private val sessionHandles = new ConcurrentHashMap[Int, BaseEnv with StreamingHandles]()

  def run(): TaskR[KernelEnvironment, Int] =
    requests
      .evalMap(handleRequest)
      .evalMap(publishResponse.publish1)
      .compile.drain.const(0)

  private def handleRequest(req: RemoteRequest): TaskR[KernelEnvironment, RemoteResponse] = ZIO.access[KernelEnvironment](identity).flatMap {
    env =>
      val response = req match {
        case QueueCellRequest(reqId, cellID)              => kernel.queueCell(cellID).flatMap {
          completed =>
            completed
              .catchAll(err => publishResponse.publish1(ResultResponse(reqId, ErrorResult(err))))
              .ensuring(publishResponse.publish1(RunCompleteResponse(reqId)).orDie)
              .supervised
              .fork
        }.const(UnitResponse(reqId))
        case CancelAllRequest(reqId)                      => kernel.cancelAll().const(UnitResponse(reqId))
        case CompletionsAtRequest(reqId, cellID, pos)     => kernel.completionsAt(cellID, pos).map(CompletionsAtResponse(reqId, _))
        case ParametersAtRequest(reqId, cellID, pos)      => kernel.parametersAt(cellID, pos).map(ParametersAtResponse(reqId, _))
        case ShutdownRequest(reqId)                       => kernel.shutdown() *> closed.complete(()).const(ShutdownResponse(reqId))
        case StatusRequest(reqId)                         => kernel.status().map(StatusResponse(reqId, _))
        case ValuesRequest(reqId)                         => kernel.values().map(ValuesResponse(reqId, _))
        case GetHandleDataRequest(reqId, sid, ht, hid, c) => kernel.getHandleData(ht, hid, c).map(GetHandleDataResponse(reqId, _)).provideSomeM(streamingHandles(sid))
        case ModifyStreamRequest(reqId, sid, hid, ops)    => kernel.modifyStream(hid, ops).map(ModifyStreamResponse(reqId, _)).provideSomeM(streamingHandles(sid))
        case ReleaseHandleRequest(reqId, sid,  ht, hid)   => kernel.releaseHandle(ht, hid).const(UnitResponse(reqId)).provideSomeM(streamingHandles(sid))
        // TODO: Kernel needs an API to release all streaming handles (then we could let go of elements from sessionHandles map; right now they will just accumulate forever)
        case req => ZIO.succeed(UnitResponse(req.reqId))
      }

      response.provide(env.withReqId(req.reqId))
  }

  private def streamingHandles(sessionId: Int): TaskR[BaseEnv, BaseEnv with StreamingHandles] =
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

  def runThrowable(args: Args): TaskR[Environment, Int] = for {
    addr            <- args.getSocketAddress
    kernelFactory   <- args.getKernelFactory
    transport       <- SocketTransport.connectClient(addr)
    updates         <- Topic[Task, Option[NotebookUpdate]](None)
    requests         = transport.requests.evalTap {
      case UpdateNotebookRequest(reqId, update) => updates.publish1(Some(update))
      case _ => ZIO.unit
    }
    publishResponse  = Publish.fn[Task, RemoteResponse](rep => transport.sendResponse(rep).provide(Environment))
    localAddress    <- effectBlocking(InetAddress.getLocalHost.getHostAddress)
    firstRequest    <- requests.head.compile.lastOrError
    initial         <- firstRequest match {
      case req@StartupRequest(_, _, _) => ZIO.succeed(req)
      case other                       => ZIO.fail(new RuntimeException(s"Handshake error; expected StartupRequest but found ${other.getClass.getName}"))
    }
    notebookRef     <- SignallingRef[Task, Notebook](initial.notebook)
    processUpdates  <- updates.subscribe(128).unNone.evalMap(update => notebookRef.update(update.applyTo))
        .compile.drain
        .fork
    interpFactories <- interpreter.Loader.load
    kernelEnv       <- mkEnv(notebookRef, firstRequest.reqId, publishResponse, interpFactories, kernelFactory, initial.config, updates.subscribe(128))
    kernel          <- kernelFactory.apply().provide(kernelEnv)
    closed          <- Deferred[Task, Unit]
    client           = new RemoteKernelClient(kernel, requests, publishResponse, closed)
    _               <- kernel.init().provide(kernelEnv)
    _               <- publishResponse.publish1(Announce(initial.reqId, localAddress))
    exitCode        <- client.run().provide(kernelEnv)
  } yield exitCode

  def mkEnv(
    currentNotebook: SignallingRef[Task, Notebook],
    reqId: Int,
    publishResponse: Publish[Task, RemoteResponse],
    interpreterFactories: Map[String, List[Interpreter.Factory]],
    kernelFactory: Factory.Service,
    polynoteConfig: PolynoteConfig,
    updates: => Stream[Task, Option[NotebookUpdate]]
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
        updates,
        taskManager,
        publishStatus
      )
    }
  }

  case class Args(address: Option[String] = None, port: Option[Int] = None, kernelFactory: Option[Kernel.Factory.Service] = None) {
    def getSocketAddress: Task[InetSocketAddress] = for {
      address       <- ZIO.fromOption(address).mapError(_ => new IllegalArgumentException("Missing required argument address"))
      port          <- ZIO.fromOption(port).mapError(_ => new IllegalArgumentException("Missing required argument address"))
      socketAddress <- ZIO(new InetSocketAddress(address, port))
    } yield socketAddress

    def getKernelFactory: Task[Kernel.Factory.Service] = ZIO.succeed(kernelFactory.getOrElse(LocalKernel))
  }

  private def parseArgs(args: List[String], current: Args = Args(), sideEffects: ZIO[Logging, Nothing, Unit] = ZIO.unit): TaskR[Logging, Args] =
    args match {
      case Nil => sideEffects *> ZIO.succeed(current)
      case "--address" :: address :: rest => parseArgs(rest, current.copy(address = Some(address)))
      case "--port" :: port :: rest => parseArgs(rest, current.copy(port = Some(port.toInt)))
      case "--kernelFactory" :: factory :: rest => parseKernelFactory(factory).flatMap(factory => parseArgs(rest, current.copy(kernelFactory = Some(factory))))
      case other :: rest => parseArgs(rest, sideEffects = sideEffects *> Logging.warn(s"Ignoring unknown argument $other"))
    }

  private def parseKernelFactory(str: String) = for {
    factoryClass <- ZIO(Class.forName(str).asSubclass(classOf[Kernel.Factory.Service]))
    factoryInst  <- ZIO(factoryClass.newInstance())
  } yield factoryInst

  case class KernelEnvironment(
    currentNotebook: Ref[Task, Notebook],
    reqId: Int,
    publishResponse: Publish[Task, RemoteResponse],
    interpreterFactories: Map[String, List[Interpreter.Factory]],
    kernelFactory: Factory.Service,
    polynoteConfig: PolynoteConfig,
    updateStream: Stream[Task, Option[NotebookUpdate]],
    taskManager: TaskManager.Service,
    publishStatus: Publish[Task, KernelStatusUpdate]
  ) extends BaseEnvT with GlobalEnvT with CellEnvT with NotebookUpdates {
    override lazy val notebookUpdates: Stream[Task, NotebookUpdate] = updateStream.unNone
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