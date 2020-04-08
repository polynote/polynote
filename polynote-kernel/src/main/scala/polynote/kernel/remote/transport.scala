package polynote.kernel
package remote

import java.io.{BufferedReader, IOException, InputStreamReader}
import java.net.{BindException, ConnectException, InetSocketAddress, Socket}
import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousCloseException, ClosedChannelException, Selector, ServerSocketChannel, SocketChannel}
import java.nio.file.Paths
import java.util.concurrent.{Semaphore, TimeUnit}

import fs2.Stream
import polynote.kernel.environment.{Config, CurrentNotebook, CurrentTask}
import polynote.kernel.logging.Logging
import polynote.kernel.networking.{FramedSocket, FramedSocketServer, Networking}
import polynote.kernel.task.TaskManager
import polynote.messages._
import scodec.Codec
import scodec.codecs.implicits._
import scodec.bits.BitVector
import scodec.stream.decode
import zio.blocking.{Blocking, effectBlocking, effectBlockingCancelable, effectBlockingInterrupt}
import zio.{Cause, Promise, RIO, RManaged, Schedule, Task, UIO, ZIO, ZManaged}
import zio.duration.{durationInt, Duration => ZDuration}
import zio.interop.catz._

import scala.concurrent.TimeoutException
import scala.reflect.{ClassTag, classTag}
import Update.notebookUpdateCodec
import cats.~>
import polynote.env.ops.Location
import zio.clock.Clock
import zio.stream.ZStream

import scala.util.Random

trait Transport[ServerAddress] {
  def serve(): RManaged[BaseEnv with GlobalEnv with CurrentNotebook with TaskManager, TransportServer[ServerAddress]]
  def connect(address: ServerAddress): RManaged[BaseEnv, TransportClient]
}


trait TransportServer[ServerAddress] {
  /**
    * The responses coming from the client
    */
  def responses: ZStream[BaseEnv, Throwable, RemoteResponse]

  /**
    * Send a request to the client
    */
  def sendRequest(req: RemoteRequest): TaskB[Unit]

  def sendNotebookUpdate(update: NotebookUpdate): TaskB[Unit]

  /**
    * Shut down the server and any processes it's deployed
    */
  def close(): TaskB[Unit]

  def awaitClosed: Task[Unit]

  /**
    * @return whether the transport is connected (i.e. can a request be sent)
    */
  def isConnected: TaskB[Boolean]

  def address: TaskB[ServerAddress]
}

trait TransportClient {
  /**
    * Send a response to the server
    */
  def sendResponse(rep: RemoteResponse): TaskB[Unit]

  /**
    * The requests coming from the server
    */
  def requests: ZStream[BaseEnv, Throwable, RemoteRequest]

  def handshake: Task[StartupRequest]

  def updates: ZStream[BaseEnv, Throwable, NotebookUpdate]

  /**
    * Shut down the client
    */
  def close(): TaskB[Unit]

  def closed: Promise[Throwable, Unit]

  def awaitClosed: Task[Unit] = closed.await
}

// TODO: need some fault tolerance mechanism here, like reconnecting on socket errors
class SocketTransportServer private (
  server: FramedSocketServer,
  channels: SocketTransport.Channels,
  private[polynote] val process: SocketTransport.DeployedProcess,
  closed: Promise[Throwable, Unit]
) extends TransportServer[InetSocketAddress] {

  override def sendRequest(req: RemoteRequest): TaskB[Unit] = for {
    msg     <- ZIO.fromEither(RemoteRequest.codec.encode(req).toEither).mapError(err => new RuntimeException(err.message))
    _       <- channels.mainChannel.send(msg.toByteBuffer)
      .onError(cause => Logging.error(s"Remote kernel failed to send request (it will probably die now)", cause))
  } yield ()


  override def sendNotebookUpdate(update: NotebookUpdate): TaskB[Unit] = for {
    msg <- ZIO.fromEither(notebookUpdateCodec.encode(update).toEither).mapError(err => new RuntimeException(err.message))
    _   <- channels.notebookUpdatesChannel.send(msg.toByteBuffer)
  } yield ()

  override val responses: ZStream[BaseEnv, Throwable, RemoteResponse] = channels.mainChannel.received.haltWhen(closed).mapM {
    buf => ZIO.fromEither(RemoteResponse.codec.decode(BitVector(buf)).toEither).map(_.value).mapError(err => new RuntimeException(err.message))
  }

  override def close(): TaskB[Unit] = closed.succeed(()).unit // *> server.close()

  override def isConnected: TaskB[Boolean] = ZIO(channels.isConnected)

  override def address: TaskB[InetSocketAddress] = server.serverAddress.flatMap {
    case null => ZIO.fail(new RuntimeException("No valid address"))
    case addr => ZIO.succeed(addr)
  }

  override def awaitClosed: Task[Unit] = closed.await
}

object SocketTransportServer {
  private def selectChannels(channel1: FramedSocket, channel2: FramedSocket): TaskB[SocketTransport.Channels] = {
    def identify(channel: FramedSocket) = channel.takeNext.flatMap {
      case Some(buf) =>
        IdentifyChannel.decodeBuffer(buf)
      case None      =>
        ZIO.fail(new IllegalStateException("No buffer was received"))
    }

    (identify(channel1) zipPar identify(channel2)).flatMap {
      case (MainChannel, NotebookUpdatesChannel) => ZIO.succeed(SocketTransport.Channels(channel1, channel2))
      case (NotebookUpdatesChannel, MainChannel) => ZIO.succeed(SocketTransport.Channels(channel2, channel1))
      case other => ZIO.fail(new IllegalStateException(s"Illegal channel set: $other"))
    }
  }

  private def monitorProcess(process: SocketTransport.DeployedProcess) =
    for {
      status <- (ZIO.sleep(ZDuration(1, TimeUnit.SECONDS)) *> process.exitStatus.orDie).doUntil(_.nonEmpty).someOrFail(SocketTransport.ProcessDied)
      _      <- Logging.info(s"Kernel process ended with $status")
      _      <- ZIO.when(status != 0)(ZIO.fail(SocketTransport.ProcessDied))
    } yield ()


  def apply(
    server: FramedSocketServer,
    channel1: FramedSocket,
    channel2: FramedSocket,
    process: SocketTransport.DeployedProcess
  ): RManaged[BaseEnv, SocketTransportServer] = for {
    closed    <- Promise.make[Throwable, Unit].toManaged(_.interrupt)
    channels  <- selectChannels(channel1, channel2).toManaged_
    _         <- monitorProcess(process).raceFirst(closed.await).to(closed).forkDaemon.toManaged_ // propagate errors from process
    _         <- channel1.awaitClosed.raceFirst(closed.await).to(closed).forkDaemon.toManaged_    // propagate errors from channel
    _         <- channel2.awaitClosed.raceFirst(closed.await).to(closed).forkDaemon.toManaged_
    transport <- ZIO.effectTotal(new SocketTransportServer(server, channels, process, closed)).toManaged(_.close().orDie)
  } yield transport
}


class SocketTransportClient private (channels: SocketTransport.Channels, val closed: Promise[Throwable, Unit], startupRequest: StartupRequest) extends TransportClient {

  def logError(msg: String)(implicit location: Location): Cause[Throwable] => ZStream[Logging, Nothing, Nothing] = {
    cause => ZStream.fromEffect(ZIO.when(!cause.interruptedOnly)(Logging.error(msg, cause))).drain
  }

  private lazy val requestStream = channels.mainChannel.received.haltWhen(closed)
    .mapM(SocketTransportClient.decodeReq)
    .catchAllCause(logError("Remote kernel client's request stream had an networking error (it will probably die now)"))

  private val updateStream = channels.notebookUpdatesChannel.received.haltWhen(closed)
    .mapM {
      buf => ZIO.fromEither(notebookUpdateCodec.decodeValue(BitVector(buf)).toEither).mapError(err => new RuntimeException(err.message))
    }.catchAllCause(logError("Remote kernel client's update stream had an networking error (it will probably die now)"))

  def sendResponse(rep: RemoteResponse): TaskB[Unit] = for {
    bytes <- ZIO.fromEither(RemoteResponse.codec.encode(rep).toEither).mapError(err => new RuntimeException(err.message))
    _     <- channels.mainChannel.send(bytes.toByteBuffer)
      .onError(Logging.error(s"Remote kernel client had an error sending a response (it will probably die now)", _))
  } yield ()

  override lazy val requests: ZStream[Logging, Nothing, RemoteRequest] = requestStream.terminateAfter(_.isInstanceOf[ShutdownRequest])

  override val handshake: Task[StartupRequest] = ZIO.succeed(startupRequest)

  override val updates: ZStream[Logging, Nothing, NotebookUpdate] = updateStream

  def close(): TaskB[Unit] =
    closed.succeed(()) *> channels.close()

}

object SocketTransportClient {

  private def decodeReq(buf: ByteBuffer): Task[RemoteRequest] =
    ZIO.fromEither(RemoteRequest.codec.decodeValue(BitVector(buf)).toEither).mapError(err => new RuntimeException(err.message))

  def apply(channels: SocketTransport.Channels): Task[SocketTransportClient] = for {
    closed     <- Promise.make[Throwable, Unit]
    startupReq <- channels.mainChannel.takeNext
      .someOrFail(new RuntimeException("No handshake received"))
      .flatMap(decodeReq)
      .flatMap {
        case req: StartupRequest => ZIO.succeed(req)
        case other               => ZIO.fail(new RuntimeException(s"Handshake error; expected StartupRequest but found ${other.getClass.getName}"))
      }
  } yield new SocketTransportClient(channels, closed, startupReq)
}

/**
  * A transport that communicates over a socket with a kernel process it's deployed via spark-submit.
  * Requires that spark-submit is a valid executable command on the path.
  */

class SocketTransport(
  deploy: SocketTransport.Deploy
) extends Transport[InetSocketAddress] {
  private[remote] def openServerChannel: RManaged[Blocking with Config with Networking, FramedSocketServer] =
    ZManaged.access[Config](_.get.kernel).flatMap {
      kernelConfig =>
        Networking.makeServer(kernelConfig.listen.getOrElse("127.0.0.1"), kernelConfig.portRange)
    }


  private def monitorProcess(process: SocketTransport.DeployedProcess): ZIO[BaseEnv, SocketTransport.ProcessDied.type, Nothing] = {
    val checkExit = ZIO.sleep(ZDuration(100, TimeUnit.MILLISECONDS)) *> process.exitStatus.orDie
    val exited    = for {
      status <- checkExit.doUntil(_.nonEmpty).get
        _      <- Logging.info(s"Kernel process ended with $status")
    } yield ()

    exited.ignore *> ZIO.fail(SocketTransport.ProcessDied)
  }

  private def deployAndServe(timeout: ZDuration = ZDuration(3, TimeUnit.MINUTES)): RManaged[BaseEnv with GlobalEnv with CurrentNotebook with TaskManager, SocketTransportServer] =
    TaskManager.runManaged("RemoteKernel", "Remote kernel", "Starting remote kernel") {
      for {
        socketServer  <- openServerChannel
        serverAddress <- socketServer.serverAddress.toManaged_
        process       <- deploy.deployKernel(this, serverAddress)
          .toManaged(_.awaitOrKill(30) orElse Logging.error("Failed to kill kernel process"))
          .tapM(_ => CurrentTask.update(_.progress(0.5, Some("Waiting for remote kernel"))))
        startConnection = socketServer.acceptM.timeoutFail(new TimeoutException(s"Remote kernel process failed to start after ${timeout.asScala}"))(timeout)
        connections   <- ZIO.mapN(startConnection, startConnection)(_ <&> _)
          .raceFirst(monitorProcess(process)).toManaged_.flatten
        (conn1, conn2) = connections
        server        <- SocketTransportServer(socketServer, conn1, conn2, process)
      } yield server
    }

  def serve(): RManaged[BaseEnv with GlobalEnv with CurrentNotebook with TaskManager, TransportServer[InetSocketAddress]] =
    deployAndServe()

  def connect(serverAddress: InetSocketAddress): RManaged[BaseEnv, TransportClient] = SocketTransport.connectClient(serverAddress)
}


object SocketTransport {
  case object ProcessDied extends Throwable("Kernel died unexpectedly")
  case class Channels(
    mainChannel: FramedSocket,
    notebookUpdatesChannel: FramedSocket
  ) {
    def isConnected: Boolean = mainChannel.isConnected && notebookUpdatesChannel.isConnected
    def close(): TaskB[Unit] = mainChannel.close().zipPar(notebookUpdatesChannel.close()).unit
    def identify(): Task[Unit] = {
      val identifyMain = mainChannel.awaitConnected *> (IdentifyChannel.encode(MainChannel) >>= mainChannel.send)
      val identifyUpdates = notebookUpdatesChannel.awaitConnected *> (IdentifyChannel.encode(NotebookUpdatesChannel) >>= notebookUpdatesChannel.send)
      identifyMain &> identifyUpdates
    }
  }

  def connectClient(serverAddress: InetSocketAddress): RManaged[BaseEnv, TransportClient] = for {
    mainChannel    <- Networking.makeClient(serverAddress)
    updatesChannel <- Networking.makeClient(serverAddress)
    channels        = SocketTransport.Channels(mainChannel, updatesChannel)
    _              <- channels.identify().toManaged_
    client         <- SocketTransportClient(channels).toManaged(_.close().orDie)
  } yield client

  /**
    * Deploys the remote kernel which will connect back to the server (for example by running spark-submit in a subprocess)
    */
  trait Deploy {
    def deployKernel(
      transport: SocketTransport,
      serverAddress: InetSocketAddress
    ): RIO[BaseEnv with GlobalEnv with CurrentNotebook, DeployedProcess]
  }

  /**
    * An interface to the process created by [[Deploy]]
    */
  trait DeployedProcess {
    def exitStatus: RIO[BaseEnv, Option[Int]]
    def awaitExit(timeout: Long, timeUnit: java.util.concurrent.TimeUnit): RIO[BaseEnv, Option[Int]]
    def kill(): RIO[BaseEnv, Unit]
    def awaitOrKill(gracePeriodSeconds: Long): RIO[BaseEnv, Unit] = awaitExit(gracePeriodSeconds, TimeUnit.SECONDS).flatMap {
      case Some(status) => ZIO.unit
      case None => kill() *> awaitExit(gracePeriodSeconds, TimeUnit.SECONDS).flatMap {
        case Some(status) => ZIO.unit
        case None => ZIO.fail(new Exception("Unable to kill deployed process"))
      }
    }
  }


  /**
    * Deployment implementation which shells out to the command line
    */
  class DeploySubprocess(deployCommand: DeploySubprocess.DeployCommand) extends Deploy {

    private def logProcess(process: Process) = {
      ZIO(new BufferedReader(new InputStreamReader(process.getInputStream))).flatMap {
        stream => effectBlocking(stream.readLine()).tap {
          case null => ZIO.unit
          case line => CurrentNotebook.path.flatMap(path => Logging.remote(path, line))
        }.repeat(Schedule.doUntil(line => line == null)).unit
      }
    }

    override def deployKernel(
      transport: SocketTransport,
      serverAddress: InetSocketAddress
    ): RIO[BaseEnv with GlobalEnv with CurrentNotebook, DeployedProcess] = deployCommand(serverAddress).flatMap {
      command =>
        val displayCommand = command.map {
          str => if (str contains " ") s""""$str"""" else str
        }.mkString(" ")

        val processBuilder = new ProcessBuilder(command: _*).redirectErrorStream(true)
        for {
          _        <- Logging.info(s"Deploying with command:\n$displayCommand")
          config   <- Config.access
          nbConfig <- CurrentNotebook.config
          _        <- ZIO {
            val processEnv = processBuilder.environment()
            (config.env ++ nbConfig.env.getOrElse(Map.empty)).foreach {
              case (k,v) => processEnv.put(k, v)
            }
          }
          process  <- effectBlocking(processBuilder.start())
          _        <- logProcess(process).forkDaemon
        } yield new DeploySubprocess.Subprocess(process)
    }
  }

  object DeploySubprocess {
    trait DeployCommand {
      def apply(serverAddress: InetSocketAddress): RIO[Config with CurrentNotebook, Seq[String]]
    }

    /**
      * Deploy by starting a Java process that inherits classpath and environment variables from this process
      */
    class DeployJava[KernelFactory <: Kernel.Factory.Service : ClassTag] extends DeployCommand {
      override def apply(serverAddress: InetSocketAddress): RIO[Config with CurrentNotebook, Seq[String]] = ZIO {
        val java = Paths.get(System.getProperty("java.home"), "bin", "java").toString
        val javaArgs = sys.process.javaVmArguments.filterNot(_ startsWith "-agentlib")
        val classPath = System.getProperty("java.class.path")
        java :: "-cp" :: classPath :: javaArgs :::
          classOf[RemoteKernelClient].getName ::
          "--address" :: serverAddress.getAddress.getHostAddress ::
          "--port" :: serverAddress.getPort.toString ::
          "--kernelFactory" :: classTag[KernelFactory].runtimeClass.getName ::
          Nil
      }
    }

    class Subprocess(process: Process) extends DeployedProcess {
      override def exitStatus: RIO[Blocking, Option[Int]] = for {
        alive <- effectBlocking(process.isAlive)
      } yield if (alive) None else Option(process.exitValue())

      override def kill(): RIO[Blocking, Unit] = effectBlocking {
        process.destroyForcibly()
      }

      override def awaitExit(timeout: Long, timeUnit: java.util.concurrent.TimeUnit): RIO[Blocking, Option[Int]] = effectBlocking {
        if (process.waitFor(timeout, timeUnit)) {
          Some(process.exitValue())
        } else {
          None
        }
      }
    }
  }
}