package polynote.kernel
package remote

import java.net.{InetAddress, InetSocketAddress}
import java.nio.channels.ClosedChannelException
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import polynote.app.Environment
import polynote.config.PolynoteConfig
import polynote.kernel.Kernel.Factory
import polynote.kernel.environment.{Config, CurrentNotebook, Env, PublishResult, PublishStatus}
import polynote.kernel.interpreter.{Interpreter, InterpreterState}
import polynote.kernel.logging.Logging
import polynote.kernel.task.TaskManager
import polynote.kernel.util.{Publish, RPublish, TPublish, UPublish}
import polynote.messages._
import polynote.runtime.{StreamingDataRepr, TableOp}
import zio.{Layer, Promise, RIO, Ref, RefM, Schedule, Semaphore, Task, UIO, URIO, ZIO, ZLayer, ZRefM, clock}
import zio.blocking.effectBlocking
import zio.clock.Clock
import zio.duration.Duration
import zio.stream.ZStream

import java.time.{DateTimeException, Instant}
import scala.collection.JavaConverters._

class RemoteKernel[ServerAddress](
  private[polynote] val transport: TransportServer[ServerAddress],
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

  private val processResponses = transport.responses.mapM {
    case KernelStatusResponse(status)    => PublishStatus(status)
    case rep@ResultResponse(_, result)   => withHandler(rep)(handler => PublishResult(result).provide(handler.env.asInstanceOf[PublishResult]))
    case rep@ResultsResponse(_, results) => withHandler(rep)(handler => PublishResult(results).provide(handler.env.asInstanceOf[PublishResult]))
    case rep: RemoteRequestResponse      => withHandler(rep)(_.run(rep).ignore)
  }

  private val interval = 120000L // 2 minutes in ms
  private val keepAliveIntervalDuration = Duration(interval, TimeUnit.MILLISECONDS)

  def init(): RIO[BaseEnv with GlobalEnv with CellEnv, Unit] = for {
    notebookRef    <- CurrentNotebook.access
    pullUpdates    <- notebookRef.updates.interruptWhen(closed.await.ignore).foreach(transport.sendNotebookUpdate).forkDaemon
    versioned      <- notebookRef.getVersioned
    (ver, notebook) = versioned
    config         <- Config.access
    process        <- processResponses.runDrain.forkDaemon
    startup        <- request(StartupRequest(nextReq, notebook, ver, config)) {
      case Announce(reqId, remoteAddress) => done(reqId, ()) // TODO: may want to keep the remote address to try reconnecting?
    }
    _              <- keepAlive().repeat(Schedule.spaced(keepAliveIntervalDuration).untilInputM(_ => closed.isDone)).forkDaemon
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

  override def cancelAll(): RIO[BaseEnv with TaskManager, Unit] = super.cancelAll() &> request(CancelRequest(nextReq, None)) {
    case UnitResponse(reqId) => done(reqId, ())
  }

  override def cancelTask(taskId: String): RIO[BaseEnv with TaskManager, Unit] = super.cancelTask(taskId) &> request(CancelRequest(nextReq, Some(taskId))) {
    case UnitResponse(reqId) => done(reqId, ())
  }

  override def tasks(): RIO[BaseEnv with TaskManager, List[TaskInfo]] = super.tasks().zip {
    request(ListTasksRequest(nextReq)) {
      case ListTasksResponse(reqId, result) => done(reqId, result)
    }
  }.map {
    case (a, b) => a ++ b
  }

  def completionsAt(id: CellID, pos: Int): RIO[BaseEnv with GlobalEnv with CellEnv, List[Completion]] =
    request(CompletionsAtRequest(nextReq, id, pos)) {
      case CompletionsAtResponse(reqId, result) => done(reqId, result)
    }

  def parametersAt(id: CellID, pos: Int): RIO[BaseEnv with GlobalEnv with CellEnv, Option[Signatures]] =
    request(ParametersAtRequest(nextReq, id, pos)) {
      case ParametersAtResponse(reqId, result) => done(reqId, result)
    }

  override def goToDefinition(idOrPath: Either[String, CellID], pos: Int): TaskC[List[DefinitionLocation]] =
    request(RemoteGoToDefinitionRequest(nextReq, idOrPath, pos)) {
      case RemoteGoToDefinitionResponse(reqId, locations) => done(reqId, locations)
    }

  override def dependencySource(language: String, dependency: String): RIO[BaseEnv with GlobalEnv, String] =
    request(RemoteDependencySourceRequest(nextReq, language, dependency)) {
      case RemoteDependencySourceResponse(reqId, source) => done(reqId, source)
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

  private def keepAlive(): TaskB[Unit] = {
    request(KeepAliveRequest(nextReq)) {
      case UnitResponse(reqId) => done(reqId, ())
    }
  }

  private[this] def close(): TaskB[Unit] = for {
    _ <- closed.succeed(())
    _ <- transport.close()
    _ <- ZIO.foreach_(waiting.values().asScala.toList)(_.promise.interrupt)
    _ <- ZIO.effectTotal(waiting.clear())
  } yield ()

  override def awaitClosed: Task[Unit] = closed.await

}

object RemoteKernel extends Kernel.Factory.Service {
  def apply[ServerAddress](transport: Transport[ServerAddress]): RIO[BaseEnv with GlobalEnv with CellEnv, RemoteKernel[ServerAddress]] = for {
    closed  <- Promise.make[Throwable, Unit]
    closing <- Semaphore.make(1L)
    server  <- transport.serve()
    kernel   = new RemoteKernel(server, closing, closed)
    _ <- (server.awaitClosed.to(closed).ensuring(kernel.shutdown().orDie)).forkDaemon
  } yield kernel

  override def apply(): RIO[BaseEnv with GlobalEnv with CellEnv, Kernel] = apply(
    new SocketTransport(
      new SocketTransport.DeploySubprocess(
        new SocketTransport.DeploySubprocess.DeployJava[LocalKernelFactory])
    ))

  def service[ServerAddress](transport: Transport[ServerAddress]): Kernel.Factory.Service = new Factory.Service {
    override def apply(): RIO[BaseEnv with GlobalEnv with CellEnv, Kernel] = RemoteKernel(transport)
  }

  def factory[ServerAddress](transport: Transport[ServerAddress]): Kernel.Factory.Service = service(transport)
}


class RemoteKernelClient(
  kernel: Kernel,
  requests: ZStream[BaseEnv, Throwable, RemoteRequest],
  publishResponse: RPublish[BaseEnv, RemoteResponse],
  cleanup: TaskB[Unit],
  closed: Promise[Throwable, Unit],
  private[remote] val notebookRef: RemoteNotebookRef, // for testing
  lastKeepAliveRef: Ref[Long]
) {

  private val sessionHandles = new ConcurrentHashMap[Int, StreamingHandles.Service]()

  private val interval = 600000L // 10 minutes in ms
  private val keepAliveIntervalDuration = Duration(interval, TimeUnit.MILLISECONDS)
  private val setKeepAlive = clock.instant.flatMap(instant => lastKeepAliveRef.set(instant.toEpochMilli));

  def run(): RIO[BaseEnv with GlobalEnv with CellEnv with PublishRemoteResponse, Unit] = {
    setKeepAlive *> checkKeepAlive().repeat(Schedule.spaced(keepAliveIntervalDuration)).forkDaemon &>
    requests
      .map(handleRequest)
      .map(z => ZStream.fromEffect(z))
      .flattenParUnbounded()
      .tap(publishResponse.publish)
      .mapM(closeOnShutdown)
      .haltWhen(closed)
      .runDrain.uninterruptible
  }

  private def checkKeepAlive(): TaskC[Unit] = clock.instant.flatMap { instant =>
    lastKeepAliveRef.get.flatMap(lastKeepAlive => {
      if (instant.toEpochMilli > lastKeepAlive + interval) {
        Logging.warn("Lost connection to server. Shutting remote kernel down...") *>
          ZIO.succeed(System.exit(10))
      } else ZIO.unit
    })
  }

  private def handleKeepAlive(reqId: Int): ZIO[Clock, DateTimeException, UnitResponse] = {
    setKeepAlive &> ZIO.succeed(UnitResponse(reqId))
  }


  private def closeOnShutdown(rep: RemoteResponse): URIO[BaseEnv, Unit] = rep match {
    case _: ShutdownResponse => close()
    case _ => ZIO.unit
  }

  private def handleRequest(req: RemoteRequest): RIO[BaseEnv with GlobalEnv with CellEnv with PublishRemoteResponse, RemoteResponse] = {
      val response = req match {
        case QueueCellRequest(reqId, cellID)              => kernel.queueCell(cellID).flatMap {
          completed =>
            completed
              .catchAll(err => publishResponse.publish(ResultResponse(reqId, ErrorResult(err))))
              .ensuring(publishResponse.publish(RunCompleteResponse(reqId)).catchAll(Logging.error))
              .forkDaemon
        }.as(UnitResponse(reqId))
        case ListTasksRequest(reqId)                      => kernel.tasks().map(ListTasksResponse(reqId, _))
        case CancelRequest(reqId, None)                   => kernel.cancelAll().as(UnitResponse(reqId))
        case CancelRequest(reqId, Some(taskId))           => kernel.cancelTask(taskId).as(UnitResponse(reqId))
        case CompletionsAtRequest(reqId, cellID, pos)     => kernel.completionsAt(cellID, pos).map(CompletionsAtResponse(reqId, _))
        case ParametersAtRequest(reqId, cellID, pos)      => kernel.parametersAt(cellID, pos).map(ParametersAtResponse(reqId, _))
        case ShutdownRequest(reqId)                       => kernel.shutdown().as(ShutdownResponse(reqId)).uninterruptible
        case StatusRequest(reqId)                         => kernel.status().map(StatusResponse(reqId, _))
        case ValuesRequest(reqId)                         => kernel.values().map(ValuesResponse(reqId, _))
        case GetHandleDataRequest(reqId, sid, ht, hid, c) => kernel.getHandleData(ht, hid, c).map(GetHandleDataResponse(reqId, _)).provideSomeLayer(streamingHandles(sid))
        case ModifyStreamRequest(reqId, sid, hid, ops)    => kernel.modifyStream(hid, ops).map(ModifyStreamResponse(reqId, _)).provideSomeLayer(streamingHandles(sid))
        case ReleaseHandleRequest(reqId, sid,  ht, hid)   => kernel.releaseHandle(ht, hid).as(UnitResponse(reqId)).provideSomeLayer(streamingHandles(sid))
        case KernelInfoRequest(reqId)                     => kernel.info().map(KernelInfoResponse(reqId, _))
        case KeepAliveRequest(reqId)                      => handleKeepAlive(reqId)
        case RemoteDependencySourceRequest(reqId, l, d)   => kernel.dependencySource(l, d).map(RemoteDependencySourceResponse(reqId, _))
        // TODO: Kernel needs an API to release all streaming handles (then we could let go of elements from sessionHandles map; right now they will just accumulate forever)
        case RemoteGoToDefinitionRequest(reqId, id, pos)  => kernel.goToDefinition(id, pos).map(RemoteGoToDefinitionResponse(reqId, _))
        case req                                          => ZIO.succeed(UnitResponse(req.reqId))
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
    publishResponse  = Publish.fn[BaseEnv, Throwable, RemoteResponse](rep => transport.sendResponse(rep).provide(baseEnv))
    localAddress    <- effectBlocking(InetAddress.getLocalHost.getHostAddress)
    firstRequest    <- requests.take(1).runHead.someOrFail(new NoSuchElementException("No messages were received"))
    initial         <- firstRequest match {
      case req@StartupRequest(_, _, _, _) => ZIO.succeed(req)
      case other                          => ZIO.fail(new RuntimeException(s"Handshake error; expected StartupRequest but found ${other.getClass.getName}"))
    }
    _               <- Env.addLayer[BaseEnv, Throwable, InterpreterState](InterpreterState.emptyLayer)
    notebookRef     <- RemoteNotebookRef(initial.globalVersion -> initial.notebook, transport)
    closed          <- Promise.make[Throwable, Unit]
    interpFactories <- interpreter.Loader.load
    _               <- Env.addLayer(mkEnv(notebookRef, firstRequest.reqId, publishResponse, interpFactories, kernelFactory, initial.config))
    kernel          <- kernelFactory.apply()
    lastKeepAliveRef <- Ref.make(0L)
    client           = new RemoteKernelClient(kernel, requests, publishResponse, transport.close(), closed, notebookRef, lastKeepAliveRef)
    _               <- tapClient.fold(ZIO.unit)(_.set(client))
    _               <- kernel.init()
    _               <- publishResponse.publish(Announce(initial.reqId, localAddress))
    _               <- client.run().ensuring(client.close())
  } yield 0

  def mkEnv(
    currentNotebook: NotebookRef,
    reqId: Int,
    publishResponse: RPublish[BaseEnv, RemoteResponse],
    interpreterFactories: Map[String, List[Interpreter.Factory]],
    kernelFactory: Factory.Service,
    polynoteConfig: PolynoteConfig
  ): ZLayer[BaseEnv with InterpreterState, Throwable, GlobalEnv with CellEnv with PublishRemoteResponse] = {
    val publishStatus = ZLayer.fromFunction[BaseEnv, UPublish[KernelStatusUpdate]] {
      baseEnv => publishResponse
        .contramap(KernelStatusResponse.apply)
        .catchAll(err => baseEnv.get[Logging.Service].error(None, err))
        .provide(baseEnv)
    }

    val publishRemote: Layer[Nothing, PublishRemoteResponse] = ZLayer.succeed(publishResponse)

    Config.layerOf(polynoteConfig) ++
      CurrentNotebook.layer(currentNotebook) ++
      ZLayer.identity[InterpreterState] ++
      Interpreter.Factories.layer(interpreterFactories) ++
      ZLayer.succeed(kernelFactory) ++
      publishStatus ++
      (publishStatus >>> TaskManager.layer) ++
      ((ZLayer.requires[BaseEnv] ++ publishRemote) >>> mkResultPublisher(reqId)) ++
      publishRemote
  }

  def mkResultPublisher(reqId: Int): ZLayer[BaseEnv with PublishRemoteResponse, Nothing, PublishResult] =
    ZLayer.fromFunction[BaseEnv with PublishRemoteResponse, UPublish[Result]] {
      env => env.get[RPublish[BaseEnv, RemoteResponse]]
        .contramap[Result](result => ResultResponse(reqId, result))
        .catchAll(env.get[Logging.Service].error(None, _))
        .provide(env)
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