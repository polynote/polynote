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
import fs2.concurrent.SignallingRef
import fs2.{Chunk, Pipe, Stream}
import polynote.app.Environment
import polynote.config.PolynoteConfig
import polynote.kernel.Kernel.Factory
import polynote.kernel.environment.{Config, CurrentNotebook, Env, NotebookUpdates, PublishResult, PublishStatus}
import polynote.kernel.interpreter.Interpreter
import polynote.kernel.logging.Logging
import polynote.kernel.task.TaskManager
import polynote.kernel.util.Publish
import polynote.messages._
import polynote.runtime.{StreamingDataRepr, TableOp}
import zio.{Cause, Layer, Promise, RIO, Semaphore, Task, UIO, URIO, ZIO, ZLayer}
import zio.blocking.effectBlocking
import zio.duration.Duration
import zio.interop.catz._

import scala.collection.JavaConverters._

class RemoteKernel[ServerAddress](
  private[polynote] val transport: TransportServer[ServerAddress],
  updates: Stream[Task, NotebookUpdate],
  closing: Semaphore,
  closed: Promise[Throwable, Unit]
) extends Kernel {

  private val requestId = new AtomicInteger(0)
  private def nextReq = requestId.getAndIncrement()

  private case class RequestHandler[R, T](handler: PartialFunction[RemoteRequestResponse, RIO[R, T]], promise: Promise[Throwable, T], env: R) {
    def run(rep: RemoteRequestResponse): UIO[Unit] = handler match {
      case handler if handler isDefinedAt rep => handler(rep).provide(env).to(promise).unit
      case _ => rep match {
        case ErrorResponse(_, err) => promise.fail(err).unit
        case _                     => ZIO.unit
      }
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
        result  <- promise.await.onError {
          cause =>
            if (!cause.interruptedOnly)
              Logging.error("Error from remote kernel", cause)
            else
              ZIO.unit
        }
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
    case rep: RemoteRequestResponse      => withHandler(rep)(_.run(rep).ignore)
  }

  def init(): RIO[BaseEnv with GlobalEnv with CellEnv, Unit] = for {                                                          // have to start pulling the updates before getting the current notebook
    pullUpdates    <- updates.evalMap(transport.sendNotebookUpdate).interruptAndIgnoreWhen(closed).compile.drain.forkDaemon   // otherwise some may be missed
    versioned      <- CurrentNotebook.getVersioned
    (ver, notebook) = versioned
    config         <- Config.access
    process        <- processResponses.compile.drain.forkDaemon
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

  override def tasks(): RIO[BaseEnv with TaskManager, List[TaskInfo]] = request(ListTasksRequest(nextReq)) {
    case ListTasksResponse(reqId, result) => done(reqId, result)
  }

  def completionsAt(id: CellID, pos: Int): RIO[BaseEnv with GlobalEnv with CellEnv, List[Completion]] =
    request(CompletionsAtRequest(nextReq, id, pos)) {
      case CompletionsAtResponse(reqId, result) => done(reqId, result)
    }

  def parametersAt(id: CellID, pos: Int): RIO[BaseEnv with GlobalEnv with CellEnv, Option[Signatures]] =
    request(ParametersAtRequest(nextReq, id, pos)) {
      case ParametersAtResponse(reqId, result) => done(reqId, result)
    }

  def shutdown(): TaskB[Unit] = closing.withPermit {
    ZIO.whenM(ZIO.mapN(closed.isDone, transport.isConnected)(!_ && _)) {
      request(ShutdownRequest(nextReq)) {
        case ShutdownResponse(reqId) =>
          done(reqId, ())
      }.timeout(Duration(10, TimeUnit.SECONDS)).flatMap {
        case Some(()) => ZIO.succeed(())
        case None =>
          Logging.warn("Waited for remote kernel to shut down for 10 seconds; killing the process")
      }
    }.ensuring {
      close().orDie
    }
  }

  def status(): TaskB[KernelBusyState] = request(StatusRequest(nextReq)) {
    case StatusResponse(reqId, status) => done(reqId, status)
  }

  def values(): TaskB[List[ResultValue]] = request(ValuesRequest(nextReq)) {
    case ValuesResponse(reqId, values) => done(reqId, values)
  }

  def getHandleData(handleType: HandleType, handle: Int, count: Int): RIO[BaseEnv with StreamingHandles, Array[ByteVector32]] =
    ZIO.access[StreamingHandles](_.get.sessionID).flatMap {
      sessionID => request(GetHandleDataRequest(nextReq, sessionID, handleType, handle, count)) {
        case GetHandleDataResponse(reqId, data) => done(reqId, data)
      }
    }

  def modifyStream(handleId: Int, ops: List[TableOp]): RIO[BaseEnv with StreamingHandles, Option[StreamingDataRepr]] =
    ZIO.access[StreamingHandles](_.get.sessionID).flatMap {
      sessionID => request(ModifyStreamRequest(nextReq, sessionID, handleId, ops)) {
        case ModifyStreamResponse(reqId, result) => done(reqId, result)
      }
    }

  def releaseHandle(handleType: HandleType, handleId: Int): RIO[BaseEnv with StreamingHandles, Unit] =
    ZIO.access[StreamingHandles](_.get.sessionID).flatMap {
      sessionID => request(ReleaseHandleRequest(nextReq, sessionID, handleType, handleId)) {
        case UnitResponse(reqId) => done(reqId, ())
      }
    }

  override def info(): TaskG[KernelInfo] = request(KernelInfoRequest(nextReq)) {
    case KernelInfoResponse(reqId, info) => done(reqId, info)
  }

  private[this] def close(): TaskB[Unit] = for {
    _ <- closed.succeed(())
    _ <- transport.close()
    _ <- waiting.values().asScala.toList.map(_.promise.interrupt).sequence
    _ <- ZIO.effectTotal(waiting.clear())
  } yield ()

  override def awaitClosed: Task[Unit] = closed.await

}

object RemoteKernel extends Kernel.Factory.Service {
  def apply[ServerAddress](transport: Transport[ServerAddress]): RIO[BaseEnv with GlobalEnv with CellEnv with NotebookUpdates, RemoteKernel[ServerAddress]] = for {
    closed  <- Promise.make[Throwable, Unit]
    closing <- Semaphore.make(1L)
    server  <- transport.serve()
    updates <- NotebookUpdates.access
    kernel   = new RemoteKernel(server, updates, closing, closed)
    _ <- (server.awaitClosed.to(closed).ensuring(kernel.shutdown().orDie)).forkDaemon
  } yield kernel

  override def apply(): RIO[BaseEnv with GlobalEnv with CellEnv with NotebookUpdates, Kernel] = apply(
    new SocketTransport(
      new SocketTransport.DeploySubprocess(
        new SocketTransport.DeploySubprocess.DeployJava[LocalKernelFactory])
    ))

  def service[ServerAddress](transport: Transport[ServerAddress]): Kernel.Factory.Service = new Factory.Service {
    override def apply(): RIO[BaseEnv with GlobalEnv with CellEnv with NotebookUpdates, Kernel] = RemoteKernel(transport)
  }

  def factory[ServerAddress](transport: Transport[ServerAddress]): Kernel.Factory.Service = service(transport)
}


class RemoteKernelClient(
  kernel: Kernel,
  requests: Stream[TaskB, RemoteRequest],
  publishResponse: Publish[Task, RemoteResponse],
  cleanup: TaskB[Unit],
  closed: Promise[Throwable, Unit],
  private[remote] val notebookRef: RemoteNotebookRef // for testing
) {

  private val sessionHandles = new ConcurrentHashMap[Int, StreamingHandles.Service]()

  def run(): RIO[BaseEnv with GlobalEnv with CellEnv with PublishRemoteResponse, Int] =
    requests
      .map(handleRequest)
      .map(Stream.eval)
      .parJoinUnbounded
      .evalTap(publishResponse.publish1)
      .evalMap(closeOnShutdown)
      .terminateWhen(closed)
      .compile.drain.uninterruptible.as(0)

  private def closeOnShutdown(rep: RemoteResponse): URIO[BaseEnv, Unit] = rep match {
    case _: ShutdownResponse => close()
    case _ => ZIO.unit
  }

  private def handleRequest(req: RemoteRequest): RIO[BaseEnv with GlobalEnv with CellEnv with PublishRemoteResponse, RemoteResponse] = {
      val response = req match {
        case QueueCellRequest(reqId, cellID)              => kernel.queueCell(cellID).flatMap {
          completed =>
            completed
              .catchAll(err => publishResponse.publish1(ResultResponse(reqId, ErrorResult(err))))
              .ensuring(publishResponse.publish1(RunCompleteResponse(reqId)).orDie)
              .forkDaemon
        }.as(UnitResponse(reqId))
        case ListTasksRequest(reqId)                      => kernel.tasks().map(ListTasksResponse(reqId, _))
        case CancelAllRequest(reqId)                      => kernel.cancelAll().as(UnitResponse(reqId))
        case CompletionsAtRequest(reqId, cellID, pos)     => kernel.completionsAt(cellID, pos).map(CompletionsAtResponse(reqId, _))
        case ParametersAtRequest(reqId, cellID, pos)      => kernel.parametersAt(cellID, pos).map(ParametersAtResponse(reqId, _))
        case ShutdownRequest(reqId)                       => kernel.shutdown().as(ShutdownResponse(reqId)).uninterruptible
        case StatusRequest(reqId)                         => kernel.status().map(StatusResponse(reqId, _))
        case ValuesRequest(reqId)                         => kernel.values().map(ValuesResponse(reqId, _))
        case GetHandleDataRequest(reqId, sid, ht, hid, c) => kernel.getHandleData(ht, hid, c).map(GetHandleDataResponse(reqId, _)).provideSomeLayer(streamingHandles(sid))
        case ModifyStreamRequest(reqId, sid, hid, ops)    => kernel.modifyStream(hid, ops).map(ModifyStreamResponse(reqId, _)).provideSomeLayer(streamingHandles(sid))
        case ReleaseHandleRequest(reqId, sid,  ht, hid)   => kernel.releaseHandle(ht, hid).as(UnitResponse(reqId)).provideSomeLayer(streamingHandles(sid))
        case KernelInfoRequest(reqId)                     => kernel.info().map(KernelInfoResponse(reqId, _))
        // TODO: Kernel needs an API to release all streaming handles (then we could let go of elements from sessionHandles map; right now they will just accumulate forever)
        case req => ZIO.succeed(UnitResponse(req.reqId))
      }

      response.interruptible.provideSomeLayer[BaseEnv with GlobalEnv with CellEnv with PublishRemoteResponse](RemoteKernelClient.mkResultPublisher(req.reqId))
  }.catchAll {
    err => ZIO.succeed(ErrorResponse(req.reqId, err))
  }

  private def streamingHandles(sessionId: Int): ZLayer[BaseEnv, Throwable, StreamingHandles] =
    ZLayer.fromEffect {
      Option(sessionHandles.get(sessionId)) match {
        case Some(handles) => ZIO.succeed(handles)
        case None => StreamingHandles.make(sessionId).map {
          handles => synchronized {
            Option(sessionHandles.get(sessionId)) match {
              case Some(handles) => handles
              case None =>
                sessionHandles.put(sessionId, handles)
                handles
            }
          }
        }
      }
    }

  def close(): URIO[BaseEnv, Unit] = cleanup.to(closed).unit
}

object RemoteKernelClient extends polynote.app.App {

  override def main(args: List[String]): ZIO[Environment, Nothing, Int] =
    (parseArgs(args) >>= runThrowable).orDie

  def runThrowable(args: Args): RIO[BaseEnv, Int] = tapRunThrowable(args, None)

  // for testing – so we can get out the remote kernel client
  def tapRunThrowable(args: Args, tapClient: Option[zio.Ref[RemoteKernelClient]]): RIO[BaseEnv, Int] = for {
    addr            <- args.getSocketAddress
    kernelFactory   <- args.getKernelFactory
    transport       <- SocketTransport.connectClient(addr)
    baseEnv         <- ZIO.environment[BaseEnv]
    requests         = transport.requests
    publishResponse  = Publish.fn[Task, RemoteResponse](rep => transport.sendResponse(rep).provide(baseEnv))
    localAddress    <- effectBlocking(InetAddress.getLocalHost.getHostAddress)
    firstRequest    <- requests.head.compile.lastOrError
    initial         <- firstRequest match {
      case req@StartupRequest(_, _, _, _) => ZIO.succeed(req)
      case other                          => ZIO.fail(new RuntimeException(s"Handshake error; expected StartupRequest but found ${other.getClass.getName}"))
    }
    notebookRef     <- RemoteNotebookRef(initial.globalVersion -> initial.notebook, transport)
    closed          <- Promise.make[Throwable, Unit]
    interpFactories <- interpreter.Loader.load
    _               <- Env.addLayer(mkEnv(notebookRef, firstRequest.reqId, publishResponse, interpFactories, kernelFactory, initial.config))
    kernel          <- kernelFactory.apply()
    client           = new RemoteKernelClient(kernel, requests, publishResponse, transport.close(), closed, notebookRef)
    _               <- tapClient.fold(ZIO.unit)(_.set(client))
    _               <- kernel.init()
    _               <- publishResponse.publish1(Announce(initial.reqId, localAddress))
    exitCode        <- client.run().ensuring(client.close())
  } yield exitCode

  def mkEnv(
    currentNotebook: NotebookRef,
    reqId: Int,
    publishResponse: Publish[Task, RemoteResponse],
    interpreterFactories: Map[String, List[Interpreter.Factory]],
    kernelFactory: Factory.Service,
    polynoteConfig: PolynoteConfig
  ): ZLayer[BaseEnv, Throwable, GlobalEnv with CellEnv with PublishRemoteResponse] = {
    val publishStatus = PublishStatus.layer(publishResponse.contramap(KernelStatusResponse.apply))
    val publishRemote: Layer[Nothing, PublishRemoteResponse] = ZLayer.succeed(publishResponse)

    Config.layerOf(polynoteConfig) ++
      CurrentNotebook.layer(currentNotebook) ++
      Interpreter.Factories.layer(interpreterFactories) ++
      ZLayer.succeed(kernelFactory) ++
      publishStatus ++
      (publishStatus >>> TaskManager.layer) ++
      (publishRemote >>> mkResultPublisher(reqId)) ++
      publishRemote
  }

  def mkResultPublisher(reqId: Int): ZLayer[PublishRemoteResponse, Nothing, PublishResult] = ZLayer.fromService {
    publishResponse: Publish[Task, RemoteResponse] => new Publish[Task, Result] {
      override def publish1(result: Result): Task[Unit] = publishResponse.publish1(ResultResponse(reqId, result))
      override def publish: Pipe[Task, Result, Unit] = results => results.mapChunks {
        resultChunk => Chunk.singleton(ResultsResponse(reqId, ShortList(resultChunk.toList)))
      }.through(publishResponse.publish)
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

}