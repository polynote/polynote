package polynote.kernel
package remote

import java.net.{InetAddress, InetSocketAddress}
import java.nio.channels.ClosedChannelException
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

import cats.Applicative
import cats.data.{NonEmptyList, Validated, ValidatedNel}

import scala.compat.java8.FunctionConverters.enrichAsJavaFunction
import cats.effect.concurrent.{Deferred, Ref}
import cats.instances.list._
import cats.syntax.traverse._
import fs2.concurrent.SignallingRef
import fs2.{Chunk, Pipe, Stream}
import polynote.config.PolynoteConfig
import polynote.kernel.Kernel.Factory
import polynote.kernel.environment.{Config, CurrentNotebook, Env, NotebookUpdates, PublishResult, PublishStatus}
import polynote.kernel.interpreter.Interpreter
import polynote.kernel.logging.Logging
import polynote.kernel.task.TaskManager
import polynote.kernel.util.Publish
import polynote.messages._
import polynote.runtime.{StreamingDataRepr, TableOp}
import zio.{Cause, Has, Layer, Promise, RIO, RManaged, Semaphore, Task, UIO, ULayer, URIO, ZIO, ZLayer, ZManaged}
import zio.blocking.effectBlocking
import zio.duration.Duration
import zio.interop.catz._
import zio.stream.ZStream

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

  private val processResponses = transport.responses.mapM {
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
    process        <- processResponses.runDrain.forkDaemon
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
    _ <- ZIO.foreachPar_(waiting.values().asScala)(_.promise.interrupt)
    _ <- ZIO.effectTotal(waiting.clear())
    _ <- closed.succeed(())
  } yield ()

  override def awaitClosed: Task[Unit] = closed.await

}

object RemoteKernel extends Kernel.Factory.Service {

  private def make[ServerAddress](
    server: TransportServer[ServerAddress]
  ): RIO[BaseEnv with NotebookUpdates, RemoteKernel[ServerAddress]] = for {
    closed  <- Promise.make[Throwable, Unit]
    closing <- Semaphore.make(1L)
    updates <- NotebookUpdates.access
    _       <- server.awaitClosed.to(closed).forkDaemon // propagate errors from the transport server
  } yield new RemoteKernel(server, updates, closing, closed)

  def apply[ServerAddress](transport: Transport[ServerAddress]): RManaged[BaseEnv with GlobalEnv with CellEnv with NotebookUpdates, RemoteKernel[ServerAddress]] = for {
    server <- transport.serve()
    kernel <- make(server).toManaged {
      _.shutdown().catchAllCause {
        cause =>
          Logging.error("Kernel shutdown error", cause)
      }
    }
  } yield kernel

  override def apply(): RManaged[BaseEnv with GlobalEnv with CellEnv with NotebookUpdates, Kernel] = apply(
    new SocketTransport(
      new SocketTransport.DeploySubprocess(
        new SocketTransport.DeploySubprocess.DeployJava[LocalKernelFactory])
    ))

  def service[ServerAddress](transport: Transport[ServerAddress]): Kernel.Factory.Service = new Factory.Service {
    override def apply(): RManaged[BaseEnv with GlobalEnv with CellEnv with NotebookUpdates, Kernel] = RemoteKernel(transport)
  }

  def factory[ServerAddress](transport: Transport[ServerAddress]): Kernel.Factory.Service = service(transport)
}


class RemoteKernelClient(
  private val kernel: Kernel,
  transport: TransportClient,
  publishResponse: Publish[Task, RemoteResponse],
  closed: Promise[Throwable, Unit],
  private[remote] val notebookRef: SignallingRef[Task, (Int, Notebook)] // for testing
) {

  private val sessionHandles = new ConcurrentHashMap[Int, StreamingHandles.Service]()

  def run(): RIO[BaseEnv with GlobalEnv with CellEnv with PublishRemoteResponse, Int] =
    transport.requests
      .mapMParUnordered(Int.MaxValue)(handleRequest)
      .tap(publishResponse.publish1)
      .terminateAfter(_.isInstanceOf[ShutdownResponse])
      .haltWhen(transport.closed)
      .haltWhen(closed)
      .runDrain.as(0)

  private def handleRequest(req: RemoteRequest): ZIO[BaseEnv with GlobalEnv with CellEnv with PublishRemoteResponse, Throwable, RemoteResponse] = {
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
        case ShutdownRequest(reqId)                       => kernel.shutdown().as(ShutdownResponse(reqId))
        case StatusRequest(reqId)                         => kernel.status().map(StatusResponse(reqId, _))
        case ValuesRequest(reqId)                         => kernel.values().map(ValuesResponse(reqId, _))
        case GetHandleDataRequest(reqId, sid, ht, hid, c) => kernel.getHandleData(ht, hid, c).map(GetHandleDataResponse(reqId, _)).provideSomeLayer(streamingHandles(sid))
        case ModifyStreamRequest(reqId, sid, hid, ops)    => kernel.modifyStream(hid, ops).map(ModifyStreamResponse(reqId, _)).provideSomeLayer(streamingHandles(sid))
        case ReleaseHandleRequest(reqId, sid,  ht, hid)   => kernel.releaseHandle(ht, hid).as(UnitResponse(reqId)).provideSomeLayer(streamingHandles(sid))
        case KernelInfoRequest(reqId)                     => kernel.info().map(KernelInfoResponse(reqId, _))
        // TODO: Kernel needs an API to release all streaming handles (then we could let go of elements from sessionHandles map; right now they will just accumulate forever)
        case req => ZIO.succeed(UnitResponse(req.reqId))
      }

      response.provideSomeLayer[BaseEnv with GlobalEnv with CellEnv with PublishRemoteResponse](
        (ZLayer.identity[PublishRemoteResponse] ++ ZLayer.succeed(req)) >>> RemoteKernelClient.mkResultPublisher
      )
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

  def close(): URIO[BaseEnv, Unit] = Logging.info("Closing kernel process") *> transport.close().to(closed).unit
  def awaitClosed: Task[Unit] = closed.await.raceAll(List(kernel.awaitClosed, transport.awaitClosed))
}

object RemoteKernelClient extends polynote.app.App {

  type ClientEnv =  GlobalEnv with CellEnv with PublishRemoteResponse
    with Has[StartupRequest]
    with Has[SignallingRef[Task, (Int, Notebook)]]
    with Has[TransportClient]

  override def main(args: List[String]): ZIO[Environment, Nothing, Int] = for {
    validArgs <- parseArgs(args, defaultArgs).orDie
    exitCode  <- runThrowable(validArgs).orDie
  } yield exitCode

  def runThrowable(args: Args[cats.Id]): RIO[Environment, Int] = tapRunThrowable(args, None)


  private def makeClient(kernel: Kernel): URIO[BaseEnv with ClientEnv, RemoteKernelClient] = for {
    closed          <- Promise.make[Throwable, Unit]
    publishResponse <- ZIO.access[PublishRemoteResponse](_.get)
    notebookRef     <- ZIO.access[Has[SignallingRef[Task, (Int, Notebook)]]](_.get)
    transport       <- ZIO.access[Has[TransportClient]](_.get)
  } yield new RemoteKernelClient(kernel, transport, publishResponse, closed, notebookRef)

  private def handshake: RIO[Has[TransportClient], StartupRequest] = {
    ZIO.access[Has[TransportClient]](_.get[TransportClient]).flatMap(_.handshake)
  }

  private def notebookUpdater: ZManaged[BaseEnv with ClientEnv, Nothing, Unit] = for {
    env             <- ZManaged.environment[ClientEnv]
    transport       <- ZManaged.access[Has[TransportClient]](_.get)
    initial         <- ZManaged.access[Has[StartupRequest]](_.get)
    notebookRef     <- ZManaged.access[CurrentNotebook](_.get)
    processUpdates  <- transport.updates.dropWhile(_.globalVersion <= initial.globalVersion)
      .mapM(update => notebookRef.update { case (ver, notebook) => update.globalVersion -> update.applyTo(notebook) })
      .runDrain.ignore.forkDaemon.toManaged(_.interruptFork)
  } yield ()

  def managedClient(args: Args[cats.Id]): ZManaged[BaseEnv with ClientEnv, Throwable, RemoteKernelClient] = for {
    kernel          <- args.kernelFactory.apply()
    client          <- makeClient(kernel).toManaged(_.close())
    processUpdates  <- notebookUpdater
  } yield client

  // for testing – so we can get out the remote kernel client
  def tapRunThrowable(args: Args[cats.Id], tapClient: Option[zio.Ref[RemoteKernelClient]]): RIO[BaseEnv, Int] =
    managedClient(args).use {
      client =>
        for {
          initial      <- ZIO.access[Has[StartupRequest]](_.get[StartupRequest])
          localAddress <- effectBlocking(InetAddress.getLocalHost.getHostAddress)
          _            <- tapClient.fold(ZIO.unit)(_.set(client))
          _            <- client.kernel.init()
          _            <- PublishRemoteResponse(Announce(initial.reqId, localAddress))
          exitCode     <- client.run()
        } yield exitCode
    }.provideSomeLayer(mkEnv(args))

  def mkEnv(
    args: Args[cats.Id]
  ): ZLayer[BaseEnv, Throwable, ClientEnv] = {
    import polynote.kernel.environment.Env.LayerOps

    val transportClient = ZLayer.fromManaged(SocketTransport.connectClient(args.getSocketAddress))
    val kernelFactory: ULayer[Kernel.Factory] = ZLayer.succeed(args.kernelFactory)
    val publishResponse = ZLayer.fromEffect {
      for {
        baseEnv         <- ZIO.environment[BaseEnv]
        transportClient <- ZIO.access[Has[TransportClient]](_.get[TransportClient])
      } yield Publish.fn[Task, RemoteResponse] {
        rep =>
          transportClient.sendResponse(rep).provide(baseEnv)
      }
    }

    val publishStatus = ZLayer.fromService[Publish[Task, RemoteResponse], Publish[Task, KernelStatusUpdate]](_.contramap(KernelStatusResponse.apply))

    val startupRequest = ZLayer.fromEffect(handshake)
    val currentRequest = ZLayer.fromService[StartupRequest, RemoteRequest](req => req)

    val versionedNotebook =
      ZLayer.fromEffect(ZIO.access[Has[StartupRequest]](_.get[StartupRequest])
        .flatMap(req => SignallingRef[Task, (Int, Notebook)](req.globalVersion -> req.notebook)))

    val notebookRef = ZLayer.fromService[SignallingRef[Task, (Int, Notebook)], Ref[Task, (Int, Notebook)]] {
      (ref: SignallingRef[Task, (Int, Notebook)]) => ref: Ref[Task, (Int, Notebook)]
    }

    Interpreter.Factories.load ++ kernelFactory ++ (transportClient andThen startupRequest).andThen {
      ZLayer.fromService[StartupRequest, PolynoteConfig](_.config) ++
      (ZLayer.identity[Has[StartupRequest]] ++ publishResponse).andThen {
        publishStatus.andThen(TaskManager.layer) ++
        (ZLayer.identity[PublishRemoteResponse] ++ currentRequest).andThen(mkResultPublisher)
      } ++ (versionedNotebook andThen notebookRef)
    }
  }

  def mkResultPublisher: ZLayer[PublishRemoteResponse with Has[RemoteRequest], Throwable, PublishResult] =
    ZLayer.fromServices[Publish[Task, RemoteResponse], RemoteRequest, Publish[Task, Result]] {
      (publishResponse, req) => Publish.fn[Task, Result] {
        result => publishResponse.publish1(ResultResponse(req.reqId, result))
      }
    }

  case class Args[F[_]](address: F[InetAddress], port: F[Int], kernelFactory: F[Kernel.Factory.LocalService]) {
    def getSocketAddress(implicit F: Applicative[F]): F[InetSocketAddress] = F.map2(address, port)(new InetSocketAddress(_, _))
    def validate(implicit F: Applicative[F]): F[Args[cats.Id]] = F.map3(address, port, kernelFactory)(Args.apply[cats.Id])
  }

  private val defaultArgs: Args[ValidatedNel[String, ?]] = Args(
    address = Validated.invalidNel[String, InetAddress]("Missing required argument address"),
    port    = Validated.invalidNel[String, Int]("Missing required argument port"),
    kernelFactory = Validated.validNel[String, Kernel.Factory.LocalService](LocalKernel)
  )

  private def parseArgs(args: List[String], current: Args[ValidatedNel[String, ?]], sideEffects: ZIO[Logging, Nothing, Unit] = ZIO.unit): RIO[Logging, Args[cats.Id]] =
    args match {
      case Nil => sideEffects *> ZIO.fromEither(current.validate.toEither).mapError(errs => new IllegalArgumentException(errs.toList.map("- " + _).mkString("\n")))
      case "--address" :: address :: rest => parseArgs(rest, current.copy(address = validateCatch(InetAddress.getByName(address))))
      case "--port" :: port :: rest => parseArgs(rest, current.copy(port = validateCatch(port.toInt)))
      case "--kernelFactory" :: factory :: rest => parseKernelFactory(factory).flatMap(factory => parseArgs(rest, current.copy(kernelFactory = factory)))
      case other :: rest => parseArgs(rest, current, sideEffects = sideEffects *> Logging.warn(s"Ignoring unknown argument $other"))
    }

  private def validateCatch[A](a: => A): ValidatedNel[String, A] =
    Validated.catchNonFatal(a).leftMap(err => NonEmptyList(err.getMessage, Nil))

  private def parseKernelFactory(str: String) = {
    for {
      factoryClass <- ZIO(Class.forName(str).asSubclass(classOf[Kernel.Factory.LocalService]))
      factoryInst  <- ZIO(factoryClass.newInstance())
    } yield factoryInst
  }.fold(err => Validated.invalidNel(err.getMessage), Validated.validNel)

}