package polynote.kernel.remote

import java.net.InetSocketAddress
import java.nio.channels.{ServerSocketChannel, SocketChannel}

import cats.effect.{ContextShift, IO}
import cats.effect.concurrent.Deferred
import cats.syntax.either._
import fs2.Stream

// WIP - refactoring the socket concerns out of RemoteSparkKernel(Client) to make it testable

trait Transport {
  def serve()(implicit contextShift: ContextShift[IO]): IO[TransportServer]
  def connect()(implicit contextShift: ContextShift[IO]): IO[TransportClient]
}

trait TransportServer {
  def responses: Stream[IO, RemoteResponse]
  def sendRequest(req: RemoteRequest): IO[Unit]
}

trait TransportClient {
  def requests: Stream[IO, RemoteRequest]
  def sendResponse(rep: RemoteResponse)
}

class SocketTransportServer(implicit contextShift: ContextShift[IO]) extends TransportServer {
  private val server = ServerSocketChannel.open().bind(new InetSocketAddress(java.net.InetAddress.getLocalHost.getHostAddress, 0))
  private val address = server.getLocalAddress.asInstanceOf[InetSocketAddress]

  private val connection = IO(server.accept()).start.unsafeRunSync()

  def sendRequest(req: RemoteRequest): IO[Unit] = for {
    channel <- connection.join
    msg     <- IO.fromEither(RemoteRequest.codec.encode(req).toEither.leftMap(err => new RuntimeException(err.message)))
    _       <- IO(channel.write(msg.toByteBuffer))
  } yield ()

  val responses: Stream[IO, RemoteResponse] = Stream.eval(connection.join).flatMap {
    channel => scodec.stream.decode.many(RemoteResponse.codec).decodeChannel[IO](channel)
  }
}

class SocketTransportClient(implicit contextShift: ContextShift[IO]) extends TransportClient {

}