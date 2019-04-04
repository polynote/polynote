package polynote.kernel.remote

import java.io.InputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{ServerSocketChannel, SocketChannel}

import cats.data.OptionT
import cats.effect.{ContextShift, Fiber, IO, Timer}
import cats.effect.concurrent.{Deferred, Ref}
import cats.syntax.apply._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import fs2.Stream
import org.log4s.getLogger
import polynote.config.PolynoteConfig
import polynote.kernel
import polynote.kernel.remote.SocketTransport.FramedSocket
import polynote.kernel.util.{ReadySignal, SparkSubmitCommand}
import polynote.messages.{Notebook, NotebookConfig}
import scodec.bits.{BitVector, ByteVector}
import scodec.stream.decode

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{Duration, SECONDS}


trait Transport[ServerAddress] {
  def serve(config: PolynoteConfig, notebook: Notebook)(implicit contextShift: ContextShift[IO]): IO[TransportServer]
  def connect(address: ServerAddress)(implicit contextShift: ContextShift[IO]): IO[TransportClient]
}

trait TransportServer {
  /**
    * The responses coming from the client
    */
  def responses: Stream[IO, RemoteResponse]

  /**
    * Send a request to the client
    */
  def sendRequest(req: RemoteRequest): IO[Unit]

  /**
    * Shut down the server and any processes it's deployed
    */
  def close(): IO[Unit]

  /**
    * @return an IO that waits for the client to connect
    */
  def connected: IO[Unit]
}

trait TransportClient {
  /**
    * Send a response to the server
    */
  def sendResponse(rep: RemoteResponse): IO[Unit]

  /**
    * The requests coming from the server
    */
  def requests: Stream[IO, RemoteRequest]

  /**
    * Shut down the client
    */
  def close(): IO[Unit]
}

// TODO: need some fault tolerance mechanism here, like reconnecting on socket errors
class SocketTransportServer(
  server: ServerSocketChannel,
  config: PolynoteConfig,
  process: SocketTransport.DeployedProcess)(implicit
  contextShift: ContextShift[IO],
  timer: Timer[IO]
) extends TransportServer {

  private val logger = getLogger

  private val connectedChannel: Deferred[IO, Either[Throwable, FramedSocket]] = Deferred.unsafe
  private val closed = ReadySignal()

  Stream.awakeEvery[IO](Duration(1, SECONDS)).evalMap(_ => process.exitStatus)
    .interruptWhen(connected.attempt)
    .evalMap {
      case Some(exitValue) if exitValue != 0 => connectedChannel.complete(Left(new RuntimeException("Remote kernel process died unexpectedly")))
      case _  => IO.unit
    }.compile.drain.unsafeRunAsyncAndForget()

  private val connection: Fiber[IO, FramedSocket] = IO(server.accept()).flatMap {
    channel =>
      val framed = new FramedSocket(channel)
      connectedChannel.complete(Right(framed)).as(framed)
  }.start.unsafeRunSync()

  override def sendRequest(req: RemoteRequest): IO[Unit] = for {
    channel <- connectedChannel.get.flatMap(IO.fromEither)
    msg     <- IO.fromEither(RemoteRequest.codec.encode(req).toEither.leftMap(err => new RuntimeException(err.message)))
    _       <- channel.write(msg)
  } yield ()

  override val responses: Stream[IO, RemoteResponse] = Stream.eval(connectedChannel.get.flatMap(IO.fromEither)).flatMap {
    channel =>
      Stream.eval(IO(logger.info("Connected. Decoding incoming messages"))).drain ++
        channel.bitVectors
          .interruptWhen(closed())
          .through(scodec.stream.decode.pipe[IO, RemoteResponse])
          .handleErrorWith {
            err => Stream.eval(IO(logger.error(err)("Response stream terminated due to error"))).drain
          } ++
        Stream.eval(IO(logger.info("Response stream terminated"))).drain
  }.onFinalize(closed.complete)

  override def close(): IO[Unit] = closed.complete *> connection.cancel.flatMap {
    _ => connectedChannel.get.flatMap {
      case Left(_) => IO.unit
      case Right(channel) => channel.close()
    }
  }

  override def connected: IO[Unit] = connectedChannel.get.flatMap(IO.fromEither).as(())
}

class SocketTransportClient(channel: FramedSocket)(implicit contextShift: ContextShift[IO]) extends TransportClient {
  private val shutdownSignal: ReadySignal = ReadySignal()
  private val requestStream = channel.bitVectors.through(decode.pipe[IO, RemoteRequest]).interruptWhen(shutdownSignal())

  private val logger = org.log4s.getLogger

  def sendResponse(rep: RemoteResponse): IO[Unit] = for {
    bytes <- IO.fromEither(RemoteResponse.codec.encode(rep).toEither.leftMap(err => new RuntimeException(err.message)))
    _     <- channel.write(bytes)
  } yield ()

  override val requests: Stream[IO, RemoteRequest] = requestStream.interruptWhen(shutdownSignal())

  def close(): IO[Unit] = channel.close() *> shutdownSignal.complete
}

/**
  * A transport that communicates over a socket with a kernel process it's deployed via spark-submit.
  * Requires that spark-submit is a valid executable command on the path.
  */
class SocketTransport(
  deploy: SocketTransport.Deploy = new SocketTransport.DeploySubprocess,
  forceServerAddress: Option[String] = None
)(
  implicit timer: Timer[IO]
) extends Transport[InetSocketAddress] {

  private val logger = org.log4s.getLogger

  private def openServerChannel: IO[ServerSocketChannel] = IO {
    ServerSocketChannel.open().bind(
      new InetSocketAddress(
        forceServerAddress.getOrElse(java.net.InetAddress.getLocalHost.getHostAddress), 0))
  }

  def serve(config: PolynoteConfig, notebook: Notebook)(implicit contextShift: ContextShift[IO]): IO[TransportServer] = for {
    socketServer <- openServerChannel
    serverAddress = socketServer.getLocalAddress.asInstanceOf[InetSocketAddress]
    process      <- deploy.deployKernel(this, config, notebook.config.getOrElse(NotebookConfig.empty), serverAddress)
  } yield new SocketTransportServer(socketServer, config, process)

  def connect(serverAddress: InetSocketAddress)(implicit contextShift: ContextShift[IO]): IO[TransportClient] = for {
    channel <- IO(SocketChannel.open(serverAddress))
    _       <- IO(logger.info(s"Connected to $serverAddress"))
  } yield new SocketTransportClient(new FramedSocket(channel))
}

object SocketTransport {

  /**
    * Deploys the remote kernel which will connect back to the server (for example by running spark-submit in a subprocess)
    */
  trait Deploy {
    def deployKernel(
      transport: SocketTransport,
      config: PolynoteConfig,
      notebookConfig: NotebookConfig,
      serverAddress: InetSocketAddress)(implicit
      contextShift: ContextShift[IO]
    ): IO[DeployedProcess]
  }

  /**
    * An interface to the process created by [[Deploy]]
    */
  trait DeployedProcess {
    def exitStatus: IO[Option[Int]]
    def kill(): IO[Unit]
  }


  /**
    * Deployment implementation which shells out to spark-submit
    */
  class DeploySubprocess extends Deploy {
    private val logger = org.log4s.getLogger
    override def deployKernel(transport: SocketTransport, config: PolynoteConfig, notebookConfig: NotebookConfig, serverAddress: InetSocketAddress)(implicit
      contextShift: ContextShift[IO]
    ): IO[DeployedProcess] = {
      val sparkConfig = config.spark ++ notebookConfig.sparkConfig.getOrElse(Map.empty)

      val isRemote = sparkConfig.get("spark.submit.deployMode") contains "cluster"

      val jarURL =
        if (isRemote)
          s"http://${serverAddress.getAddress.getHostAddress}:${config.listen.port}/polynote-assembly.jar"
        else
          getClass.getProtectionDomain.getCodeSource.getLocation.getPath

      val baseCommand = SparkSubmitCommand(sparkConfig, classOf[RemoteSparkKernelClient].getName, jarURL)

      val serverHostPort = s"${serverAddress.getAddress.getHostAddress}:${serverAddress.getPort}"

      val command = baseCommand ++ Seq("--remoteAddress", serverHostPort)

      val displayCommand = command.map {
        str => if (str contains " ") s""""$str"""" else str
      }.mkString(" ")

      for {
        _       <- IO(logger.info(s"Running deploy command: $displayCommand"))
        process <- IO(new ProcessBuilder(command: _*).inheritIO().start())
      } yield new DeployedSubprocess(process)
    }
  }

  class DeployedSubprocess(process: Process) extends DeployedProcess {
    override def exitStatus: IO[Option[Int]] = for {
      alive <- IO(process.isAlive)
    } yield if (alive) None else Option(process.exitValue())

    override def kill(): IO[Unit] = IO(process.destroy())
  }

  /**
    * Produces a stream of [[BitVector]]s from a [[SocketChannel]]. We should be able to use [[scodec.stream.decode.StreamDecoder.decodeChannel]]
    * instead, but it doesn't seem to emit anything. So this auxiliary class is used instead.
    *
    * It reads a framed message into a single [[ByteBuffer]]. The message must be framed by preceeding it with a
    * signed 32-bit big-endian length, not including the 4 bytes of the length itself.
    *
    * It also includes a method to write such a framed message to the channel from a [[BitVector]].
    */
  // TODO: Maybe fs2.io.tcp.Socket methods could be made to work, just seems over-complicated for single-client server?
  // TODO: If this introduces allocation/GC latency, could try to use a shared, reused buffer
  class FramedSocket(socketChannel: SocketChannel, keepalive: Boolean = true)(implicit contextShift: ContextShift[IO], timer: Timer[IO]) {
    private val completed = ReadySignal()
    private val incomingLengthBuffer = ByteBuffer.allocate(4)
    private val outgoingLengthBuffer = ByteBuffer.allocate(4)

    // send 0-length frames to keep connection alive
    if (keepalive) {
      Stream.awakeEvery[IO](Duration(5, SECONDS)).evalMap(_ => write(BitVector.empty))
        .interruptWhen(completed())
        .compile
        .drain
        .unsafeRunAsyncAndForget()
    }

    private def read(): Option[ByteBuffer] = incomingLengthBuffer.synchronized {
      incomingLengthBuffer.rewind()
      while(incomingLengthBuffer.hasRemaining) {
        if(socketChannel.read(incomingLengthBuffer) == -1) {
          completed.completeSync()
          return None
        }
      }

      val len = incomingLengthBuffer.getInt(0)
      if (len <= 0) {
        None
      } else {
        val msgBuffer = ByteBuffer.allocate(len)
        while (msgBuffer.hasRemaining) {
          socketChannel.read(msgBuffer)
        }

        msgBuffer.rewind()
        Some(msgBuffer)
      }
    }

    def write(msg: BitVector): IO[Unit] = IO {
      val byteVector = msg.toByteVector
      val size = byteVector.size.toInt
      val byteBuffer = byteVector.toByteBuffer

      outgoingLengthBuffer.synchronized {
        outgoingLengthBuffer.rewind()
        outgoingLengthBuffer.putInt(0, size)
        socketChannel.write(outgoingLengthBuffer)

        while (byteBuffer.hasRemaining) {
          socketChannel.write(byteBuffer)
        }
      }
    }

    def close(): IO[Unit] = IO(outgoingLengthBuffer.synchronized(socketChannel.close()))

    val bitVectors: Stream[IO, BitVector] =
      Stream.repeatEval(IO(read())).unNone
        .map(BitVector.view)
        .interruptWhen(completed())
  }
}