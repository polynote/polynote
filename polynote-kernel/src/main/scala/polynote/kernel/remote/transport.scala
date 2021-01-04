package polynote.kernel
package remote

import java.io.{BufferedReader, File, IOException, InputStreamReader}
import java.net.{BindException, ConnectException, InetSocketAddress, Socket}
import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousCloseException, ClosedChannelException, ServerSocketChannel, SocketChannel}
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.{Semaphore, TimeUnit}
import fs2.Stream
import polynote.buildinfo.BuildInfo
import polynote.kernel.environment.{Config, CurrentNotebook, CurrentTask}
import polynote.kernel.logging.Logging
import polynote.kernel.remote.SocketTransport.FramedSocket
import polynote.messages._
import scodec.Codec
import scodec.codecs.implicits._
import scodec.bits.BitVector
import scodec.stream.decode
import zio.blocking.{Blocking, effectBlocking, effectBlockingCancelable, effectBlockingInterrupt}
import zio.{Cause, Promise, RIO, Schedule, Task, URIO, ZIO, system => ZSystem}
import zio.duration.{DurationOps, durationInt, Duration => ZDuration}
import zio.interop.catz._
import zio.system.{env, property}

import scala.concurrent.TimeoutException
import scala.reflect.{ClassTag, classTag}
import Update.notebookUpdateCodec
import cats.~>
import polynote.kernel.task.TaskManager
import polynote.kernel.util.listFiles

import java.util.function.IntFunction
import scala.annotation.tailrec
import scala.util.Random
import scala.util.control.NonFatal

trait Transport[ServerAddress] {
  def serve(): RIO[BaseEnv with GlobalEnv with CurrentNotebook with TaskManager, TransportServer[ServerAddress]]
  def connect(address: ServerAddress): TaskB[TransportClient]
}


trait TransportServer[ServerAddress] {
  /**
    * The responses coming from the client
    */
  def responses: Stream[TaskB, RemoteResponse]

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
  def requests: Stream[TaskB, RemoteRequest]

  def updates: Stream[TaskB, NotebookUpdate]

  /**
    * Shut down the client
    */
  def close(): TaskB[Unit]
}

// TODO: need some fault tolerance mechanism here, like reconnecting on socket errors
class SocketTransportServer private (
  server: ServerSocketChannel,
  channels: SocketTransport.Channels,
  private[polynote] val process: SocketTransport.DeployedProcess,
  closed: Promise[Throwable, Unit]
) extends TransportServer[InetSocketAddress] {

  override def sendRequest(req: RemoteRequest): TaskB[Unit] = for {
    msg     <- ZIO.fromEither(RemoteRequest.codec.encode(req).toEither).mapError(err => new RuntimeException(err.message))
    _       <- channels.mainChannel.write(msg).onError(cause => Logging.error(s"Remote kernel failed to send request (it will probably die now)", cause))
  } yield ()

  private val updateCodec = Codec[NotebookUpdate]

  override def sendNotebookUpdate(update: NotebookUpdate): TaskB[Unit] = for {
    msg <- ZIO.fromEither(updateCodec.encode(update).toEither).mapError(err => new RuntimeException(err.message))
    _   <- channels.notebookUpdatesChannel.write(msg)
  } yield ()

  override val responses: Stream[TaskB, RemoteResponse] =
        channels.mainChannel.bitVectors
          .interruptAndIgnoreWhen(closed)
          .through(scodec.stream.decode.pipe[TaskB, RemoteResponse])

  override def close(): TaskB[Unit] = closed.succeed(()) *> channels.close() *> process.awaitOrKill(30)

  override def isConnected: TaskB[Boolean] = ZIO(channels.isConnected)

  override def address: TaskB[InetSocketAddress] = effectBlocking(Option(server.getLocalAddress)).flatMap {
    case Some(addr: InetSocketAddress) => ZIO.succeed(addr)
    case _ => ZIO.fail(new RuntimeException("No valid address"))
  }

  override def awaitClosed: Task[Unit] = closed.await
}

object SocketTransportServer {
  private def selectChannels(channel1: FramedSocket, channel2: FramedSocket, address: InetSocketAddress): TaskB[SocketTransport.Channels] = {
    def identify(channel: FramedSocket) = channel.read().repeat {
      Schedule.recurUntil[Option[Option[ByteBuffer]]] {
        case Some(Some(_)) => true
        case _ => false
      }
    }.flatMap {
      case Some(Some(buf)) => IdentifyChannel.decodeBuffer(buf)
      case _               => ZIO.fail(new IllegalStateException("No buffer was received"))
    }

    (identify(channel1) zipPar identify(channel2)).flatMap {
      case (MainChannel, NotebookUpdatesChannel) => ZIO.succeed(SocketTransport.Channels(channel1, channel2, address))
      case (NotebookUpdatesChannel, MainChannel) => ZIO.succeed(SocketTransport.Channels(channel2, channel1, address))
      case other => ZIO.fail(new IllegalStateException(s"Illegal channel set: $other"))
    }
  }

  private def monitorProcess(process: SocketTransport.DeployedProcess) =
    for {
      status <- (ZIO.sleep(ZDuration(1, TimeUnit.SECONDS)) *> process.exitStatus).repeatUntil(_.nonEmpty).someOrFail(SocketTransport.ProcessDied)
      _      <- Logging.info(s"Kernel process ended with $status")
      _      <- ZIO.when(status != 0)(ZIO.fail(SocketTransport.ProcessDied))
    } yield ()


  def apply(
    server: ServerSocketChannel,
    channel1: FramedSocket,
    channel2: FramedSocket,
    process: SocketTransport.DeployedProcess
  ): TaskB[SocketTransportServer] = for {
    closed   <- Promise.make[Throwable, Unit]
    channels <- selectChannels(channel1, channel2, server.getLocalAddress.asInstanceOf[InetSocketAddress])
    _        <- monitorProcess(process).to(closed).forkDaemon
    _        <- channel1.awaitClosed.to(closed).forkDaemon
    _        <- channel2.awaitClosed.to(closed).forkDaemon
    transport = new SocketTransportServer(server, channels, process, closed)
    _        <- closed.await.ensuring(transport.close().orDie).ignore.forkDaemon
  } yield transport
}

class SocketTransportClient private (channels: SocketTransport.Channels, closed: Promise[Throwable, Unit]) extends TransportClient {

  def logError(fn: Cause[Throwable] => ZIO[Logging, Nothing, Unit]): TaskB ~> TaskB = new ~>[TaskB, TaskB] {
    override def apply[A](fa: TaskB[A]): TaskB[A] = fa.onError {
      cause => ZIO.when(!cause.interruptedOnly)(fn(cause))
    }
  }

  private val requestStream = channels.mainChannel.bitVectors.interruptAndIgnoreWhen(closed)
    .translate(logError(Logging.error("Remote kernel client's request stream had an networking error (it will probably die now)", _)))
    .through(decode.pipe[TaskB, RemoteRequest])

  private val updateStream = channels.notebookUpdatesChannel.bitVectors.interruptAndIgnoreWhen(closed)
    .translate(logError(Logging.error("Remote kernel client's update stream had an networking error (it will probably die now)", _)))
    .through(decode.pipe[TaskB, NotebookUpdate])

  def sendResponse(rep: RemoteResponse): TaskB[Unit] = for {
    bytes <- ZIO.fromEither(RemoteResponse.codec.encode(rep).toEither).mapError(err => new RuntimeException(err.message))
    _     <- channels.mainChannel.write(bytes)
      .onError(Logging.error(s"Remote kernel client had an error sending a response (it will probably die now)", _))
  } yield ()

  override val requests: Stream[TaskB, RemoteRequest] = requestStream.terminateAfter(_.isInstanceOf[ShutdownRequest])

  override val updates: Stream[TaskB, NotebookUpdate] = updateStream.interruptAndIgnoreWhen(closed)

  def close(): TaskB[Unit] = closed.succeed(()) *> channels.close()
}

object SocketTransportClient {
  def apply(channels: SocketTransport.Channels): Task[SocketTransportClient] = for {
    closed <- Promise.make[Throwable, Unit]
  } yield new SocketTransportClient(channels, closed)
}

/**
  * A transport that communicates over a socket with a kernel process it's deployed via spark-submit.
  * Requires that spark-submit is a valid executable command on the path.
  */
class SocketTransport(
  deploy: SocketTransport.Deploy
) extends Transport[InetSocketAddress] {

  private[remote] def openServerChannel: RIO[Blocking with Config, ServerSocketChannel] =
    ZIO.mapN(Config.access, ZIO(ServerSocketChannel.open())) {
      (config, socket) =>
        val address = config.kernel.listen.getOrElse("127.0.0.1")
        def bindTo(port: Int) =
          effectBlocking(socket.bind(new InetSocketAddress(address, port)))

        val bind = config.kernel.portRange match {
          case None        => bindTo(0)
          case Some(range) =>
            ZIO.firstSuccessOf(bindTo(range.head), range.tail.map(bindTo))
              .orElseFail(new BindException(s"Unable to bind to any port in range ${range.start}-${range.end} on $address"))
        }

        bind.as(socket)
    }.flatten

  private def startConnection(
    server: ServerSocketChannel,
    timeout: ZDuration = 3.minutes
  ): TaskB[FramedSocket] = {
    for {
      channel <- effectBlockingCancelable(server.accept())(ZIO.effectTotal(server.close()))
      framed  <- FramedSocket(channel, keepalive = true)
    } yield framed
  }.timeoutFail(new TimeoutException(s"Remote kernel process failed to start after ${timeout.render}"))(timeout)

  private def monitorProcess(process: SocketTransport.DeployedProcess) = {
    val checkExit = ZIO.sleep(ZDuration(100, TimeUnit.MILLISECONDS)) *> process.exitStatus
    val exited    = for {
      status <- checkExit.repeatUntil(_.nonEmpty).get
      _      <- Logging.info(s"Kernel process ended with $status")
    } yield ()

    exited.ignore *> ZIO.fail(SocketTransport.ProcessDied)
  }

  private[polynote] def deployAndServe(): RIO[BaseEnv with GlobalEnv with CurrentNotebook with TaskManager, (TransportServer[InetSocketAddress], SocketTransport.DeployedProcess)] =
    TaskManager.run("RemoteKernel", "Remote kernel", "Starting remote kernel") {
      for {
        socketServer  <- openServerChannel
        serverAddress  = socketServer.getLocalAddress.asInstanceOf[InetSocketAddress]
        process       <- deploy.deployKernel(this, serverAddress)
        _             <- CurrentTask.update(_.progress(0.5, Some("Waiting for remote kernel")))
        connection    <- startConnection(socketServer).raceFirst(monitorProcess(process))
        connection2   <- startConnection(socketServer)
        server        <- SocketTransportServer(socketServer, connection, connection2, process)
      } yield (server, process)
    }

  def serve(): RIO[BaseEnv with GlobalEnv with CurrentNotebook with TaskManager, TransportServer[InetSocketAddress]] =
    deployAndServe().map(_._1)

  def connect(serverAddress: InetSocketAddress): TaskB[TransportClient] = SocketTransport.connectClient(serverAddress)
}

object SocketTransport {
  case object ProcessDied extends Throwable("Kernel died unexpectedly")
  case class Channels(
    mainChannel: FramedSocket,
    notebookUpdatesChannel: FramedSocket,
    address: InetSocketAddress
  ) {
    def isConnected: Boolean = mainChannel.isConnected && notebookUpdatesChannel.isConnected
    def close(): TaskB[Unit] = mainChannel.close().zipPar(notebookUpdatesChannel.close()).unit
  }

  def connectClient(serverAddress: InetSocketAddress): TaskB[TransportClient] = for {
    mainChannel    <- effectBlocking(SocketChannel.open(serverAddress)) >>= (FramedSocket(_, keepalive = true))
    updatesChannel <- effectBlocking(SocketChannel.open(serverAddress)) >>= (FramedSocket(_, keepalive = true))
    _              <- IdentifyChannel.encode(MainChannel) >>= mainChannel.write
    _              <- IdentifyChannel.encode(NotebookUpdatesChannel) >>= updatesChannel.write
    channels        = SocketTransport.Channels(mainChannel, updatesChannel, serverAddress)
    client         <- SocketTransportClient(channels)
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
    def exitStatus: URIO[BaseEnv, Option[Int]]
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
    * Deployment implementation which shells out to spark-submit
    */
  class DeploySubprocess(deployCommand: DeploySubprocess.DeployCommand) extends Deploy {

    private def logProcess(process: Process) = {
      ZIO(new BufferedReader(new InputStreamReader(process.getInputStream))).flatMap {
        stream => effectBlocking(stream.readLine()).tap {
          case null => ZIO.unit
          case line => CurrentNotebook.path.flatMap(path => Logging.remote(path, line))
        }.repeatUntil(_ == null).unit
      }
    }

    private def findScalaVersion: URIO[BaseEnv with GlobalEnv with CurrentNotebook, String] = for {
      serverConfig   <- Config.access
      notebookConfig <- CurrentNotebook.config
      scalaVersion   <- ZIO.succeed(notebookConfig.scalaVersion).some
        .orElse(ZIO.succeed(serverConfig.kernel.scalaVersion).some)
        .orElse(deployCommand.detectScalaVersion.some)
        .orElse(ZIO.succeed(DeploySubprocess.DefaultScalaVersion))
    } yield scalaVersion

    private def listJars(path: Path): RIO[BaseEnv, Seq[Path]] = listFiles(path)
      .map(_.filter(_.getFileName.toString.endsWith(".jar")))
      .tapError {
        case NonFatal(err) => Logging.warn(s"Failed to list JARs in $path", err).as(Seq.empty)
      }.flatMap {
        paths => ZIO.collect(paths)(path => effectBlocking(path.toRealPath().toAbsolutePath).asSomeError)
      }

    private def listJarsForVersion(dir: String, scalaVersion: String): RIO[BaseEnv, Seq[Path]] =
      ZSystem.property("user.dir").flatMap {
        case Some(cwd) => ZIO(Paths.get(cwd, dir, scalaVersion)).flatMap(listJars)
        case None      => ZIO.succeed(Seq.empty)
      }

    // For inheriting the classpath of the server process – this is mainly so that you can run from the IDE
    // without having built the distribution.
    private def currentClasspath: URIO[zio.system.System, List[Path]] = zio.system.property("java.class.path").some
      .map(_.split(File.pathSeparatorChar).toList.map(path => Paths.get(path)))
      .orElse(ZIO.succeed(Nil))

    private def buildClassPath(scalaVersion: String): RIO[BaseEnv, Seq[Path]] = zio.system.env("POLYNOTE_INHERIT_CLASSPATH").flatMap {
      case None =>
        for {
          deps    <- listJarsForVersion("deps", scalaVersion).orElse(currentClasspath)
          plugins <- listJarsForVersion("plugins.d", scalaVersion).orElseSucceed(Nil)
        } yield deps ++ plugins

      case Some(_) => currentClasspath
    }

    private def buildCommand(
      serverAddress: InetSocketAddress
    ): RIO[BaseEnv with GlobalEnv with CurrentNotebook, Seq[String]] = for {
      classPath <- findScalaVersion >>= buildClassPath
      command   <- deployCommand(serverAddress, classPath)
    } yield command

    override def deployKernel(
      transport: SocketTransport,
      serverAddress: InetSocketAddress
    ): RIO[BaseEnv with GlobalEnv with CurrentNotebook, DeployedProcess] = buildCommand(serverAddress).flatMap {
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
    val DefaultScalaVersion = "2.11"

    trait DeployCommand {
      def apply(serverAddress: InetSocketAddress, classPath: Seq[Path]): RIO[BaseEnv with Config with CurrentNotebook, Seq[String]]
      def detectScalaVersion: URIO[BaseEnv with Config with CurrentNotebook, Option[String]] =
        ZIO(BuildInfo.scalaBinaryVersion).option
    }

    /**
      * Deploy by starting a Java process that inherits classpath and environment variables from this process
      */
    class DeployJava[KernelFactory <: Kernel.Factory.Service : ClassTag] extends DeployCommand {
      private def findJava: URIO[BaseEnv, String] =
        property("java.home").mapError(_.getMessage).someOrFail("No java.home property is set")
          .map(home => Paths.get(home, "bin", "java").toString)
          .tapError(err => Logging.warn("Couldn't find java executable; will just use 'java' ($err)"))
          .orElse(ZIO.succeed("java"))

      // parse a JVM args string into a list of args
      private def parseJVMArgs(str: String): List[String] = {
        val searchQuoted = raw"""^((?:[^"\\]|\\.)*)"""".r

        def parseQuoted(rest: String): (String, String) = searchQuoted.findFirstMatchIn(rest) match {
          case None    => (rest, "")
          case Some(m) => (m.group(1), rest.drop(m.end))
        }

        def parseUnquoted(rest: String): (String, String) = rest.indexOf(' ') match {
          case -1 => (rest, "")
          case n  => (rest.take(n), rest.drop(n + 1).dropWhile(_ == ' '))
        }

        def parseNext(rest: String): (String, String) = rest match {
          case ""                     => ("", "")
          case str if str.head == '"' => parseQuoted(str.tail)
          case str                    => parseUnquoted(str)
        }

        @tailrec
        def parse(rest: String, accum: List[String]): List[String] = rest match {
          case ""   => accum.reverse
          case rest =>
            val (arg, remainder) = parseNext(rest)
            parse(remainder, arg :: accum)
        }

        parse(str, Nil)
      }

      override def apply(serverAddress: InetSocketAddress, classPath: Seq[Path]): RIO[BaseEnv with Config with CurrentNotebook, Seq[String]] = {
        for {
          notebookConfig   <- CurrentNotebook.config
          java             <- findJava
        } yield {
          val fullClassPath = classPath.map(_.toString)
            .filterNot(_.isEmpty)
            .mkString(File.pathSeparator)

          val javaArgs = notebookConfig.jvmArgs.toList.flatMap(parseJVMArgs)

          java :: "-cp" :: fullClassPath :: javaArgs :::
            classOf[RemoteKernelClient].getName ::
            "--address" :: serverAddress.getAddress.getHostAddress ::
            "--port" :: serverAddress.getPort.toString ::
            "--kernelFactory" :: classTag[KernelFactory].runtimeClass.getName ::
            Nil
        }
      }
    }

    class Subprocess(process: Process) extends DeployedProcess {
      override def exitStatus: URIO[Blocking, Option[Int]] = for {
        alive <- effectBlocking(process.isAlive).orDie
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
  class FramedSocket(socketChannel: SocketChannel, closed: Promise[Throwable, Unit]) {
    private val incomingLengthBuffer = ByteBuffer.allocate(4)
    private val outgoingLengthBuffer = ByteBuffer.allocate(4)

    // using primitive j.u.concurrent Semaphore here, because I need tryAcquire (zio Semaphore doesn't have it)
    // TODO: When zio Sempaphore has tryAcquire, use that instead
    private val writeLock = new Semaphore(1)

    private def readBuffer(): Option[Option[ByteBuffer]] = incomingLengthBuffer.synchronized {
      incomingLengthBuffer.rewind()
      while(incomingLengthBuffer.hasRemaining) {
        if(socketChannel.read(incomingLengthBuffer) == -1) {
          return None
        }
      }

      val len = incomingLengthBuffer.getInt(0)
      if (len < 0) {
        None
      } else if (len == 0) {
        Some(None)
      } else {
        val msgBuffer = ByteBuffer.allocate(len)
        while (msgBuffer.hasRemaining) {
          socketChannel.read(msgBuffer)
        }

        msgBuffer.rewind()
        Some(Some(msgBuffer))
      }
    }

    def read(): TaskB[Option[Option[ByteBuffer]]] = effectBlocking(readBuffer()).catchSome {
      case err: ClosedChannelException => Logging.info("Remote peer closed connection") *> close() *> ZIO.succeed(None)
    }.tapError {
      err => closed.fail(err)
    }

    def write(msg: BitVector): TaskB[Unit] = effectBlocking(writeLock.acquire()).bracket(_ => ZIO.effectTotal(writeLock.release())) {
      _ => effectBlocking {
        val byteVector = msg.toByteVector
        val size = byteVector.size.toInt
        writeSize(size)
        val byteBuffer = byteVector.toByteBuffer
        while (byteBuffer.hasRemaining) {
          socketChannel.write(byteBuffer)
        }
      }
    }.tapError {
      case err: ClosedChannelException => close()
      case err => closed.fail(err) *> close()
    }

    // MUST SYNCHRONIZE on writeLock to invoke this!
    private def writeSize(size: Int) = {
      outgoingLengthBuffer.rewind()
      outgoingLengthBuffer.putInt(0, size)
      socketChannel.write(outgoingLengthBuffer)
    }

    // send a keepalive, but if the channel is already being written, do nothing (don't queue a keepalive)
    def sendKeepalive(): TaskB[Unit] = ZIO.effectTotal(writeLock.tryAcquire(0, TimeUnit.SECONDS))
      .bracket(acquired => ZIO.when(acquired)(ZIO.effectTotal(writeLock.release()))) {
        acquired => ZIO.when(acquired) {
          effectBlocking(writeSize(0))
        }.catchAll {
          err => closed.isDone.flatMap {
            case true => ZIO.unit
            case false => ZIO.fail(err)
          }
        }
      }

    def close(): TaskB[Unit] = closed.succeed(()).flatMap {
      case true =>
        ZIO.effect { socketChannel.shutdownInput(); socketChannel.shutdownOutput() } *>
          effectBlocking(writeLock.acquire()).bracket(_ => ZIO.effectTotal(writeLock.release())) {
            _ => effectBlocking(socketChannel.close()).uninterruptible
          }
      case false => ZIO.unit
    }

    def isConnected: Boolean = socketChannel.isConnected

    def awaitClosed: Task[Unit] = closed.await

    val bitVectors: Stream[TaskB, BitVector] =
      Stream.repeatEval(read())
        .unNoneTerminate.unNone
        .map(BitVector.view)
  }

  object FramedSocket {
    private val keepaliveDuration = ZDuration(250, TimeUnit.MILLISECONDS)
    def apply(socketChannel: SocketChannel, keepalive: Boolean = true): TaskB[FramedSocket] = {
      for {
        closed       <- Promise.make[Throwable, Unit]
        framedSocket  = new FramedSocket(socketChannel, closed)
        doKeepalive  <- if (keepalive) {
          // This sends a keepalive quite frequently, because it's the only way we can detect if the remote peer dies.
          // It only sends 16 bytes per second, though, and they only send if the channel isn't being written.
          (ZIO.yieldNow *> framedSocket.sendKeepalive()).retry(Schedule.recurs(2).addDelay(_ => keepaliveDuration)).tapError {
            err =>
              closed.fail(err) *> effectBlocking(socketChannel.close())
          }.repeat(Schedule.spaced(keepaliveDuration)).raceFirst(closed.await.ignore).forkDaemon
        } else ZIO.unit
      } yield framedSocket
    }
  }
}