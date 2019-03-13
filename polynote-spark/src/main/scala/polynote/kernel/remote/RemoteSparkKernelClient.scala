package polynote.kernel.remote

import java.net.InetSocketAddress
import java.nio.channels.AsynchronousChannelGroup
import java.util.concurrent.{ExecutorService, Executors}

import cats.effect.{ContextShift, ExitCode, IO, IOApp}
import fs2.Stream
import fs2.io.tcp.Socket
import polynote.messages.Message
import scodec.stream.decode

import scala.concurrent.ExecutionContext

/**
  * This is the main class instantiated in a remote Spark situation; it starts the actual SparkPolyKernel and connects
  * back to the RemoteSparkKernel server.
  */
class RemoteSparkKernelClient(
  remoteAddress: InetSocketAddress
) extends Serializable {

  private val executorService: ExecutorService = Executors.newCachedThreadPool()
  private implicit val contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.fromExecutorService(executorService))
  private implicit val channelGroup: AsynchronousChannelGroup = AsynchronousChannelGroup.withCachedThreadPool(executorService, 1)
  private val client = Socket.client(remoteAddress)


  def run(): IO[ExitCode] = client.use {
    socket =>
      Stream.repeatEval(socket.read(8192)).unNone.through(decode.many(Message.codec).decodeInputStream()
  }

}
