package polynote.kernel

import java.io.Closeable
import java.net.InetSocketAddress
import java.nio.channels.{SelectionKey, Selector, SocketChannel}
import java.util.concurrent.TimeUnit

import scala.collection.JavaConverters._
import zio.{Fiber, Has, Managed, RIO, RManaged, TaskManaged, UIO, URIO, URManaged, ZIO, ZLayer, ZManaged}
import zio.ZIO.{effect, effectTotal}
import zio.blocking.{Blocking, effectBlocking, effectBlockingCancelable}
import zio.duration.Duration

package object networking {
  type Networking = Has[NetworkingService]
  object Networking {
    val live: ZLayer[Blocking, Throwable, Networking] = ZLayer.fromManaged(NetworkingService.make())

    def access: URIO[Networking, NetworkingService] = ZIO.access[Networking](_.get)
    def accessManaged: URManaged[Networking, NetworkingService] = ZManaged.access[Networking](_.get)
    def makeServer(address: InetSocketAddress): RManaged[Networking, FramedSocketServer] = accessManaged.flatMap(_.makeServer(address))
    def makeServer(host: String, port: Option[Range]): RManaged[Blocking with Networking, FramedSocketServer] = accessManaged.flatMap(_.makeServer(host, port))
    def makeClient(address: InetSocketAddress): RManaged[Networking, FramedSocket] = accessManaged.flatMap(_.makeClient(address))
  }

  private[networking] def effectClose(closeable: Closeable): UIO[Unit] = ZIO(closeable.close()).ignore

  final class NetworkingService private (selector: Selector, selectInterval: Duration) {

    def makeServer(address: InetSocketAddress): TaskManaged[FramedSocketServer] =
      FramedSocketServer.open(address, selector)

    def makeServer(host: String, port: Option[Range]): RManaged[Blocking, FramedSocketServer] = port match {
      case None => effect(new InetSocketAddress(host, 0)).toManaged_.flatMap(makeServer)
      case Some(range) => FramedSocketServer.open(host, range, selector)
    }

    def makeClient(address: InetSocketAddress): TaskManaged[FramedSocket] =
      FramedSocket.client(address, selector)

    private val intervalMillis = selectInterval.toMillis

    private def handleKey(key: SelectionKey) = key.attachment() match {
      case socket: FramedSocket =>
        val connect = ZIO.when(key.isConnectable)(socket.doConnect)
        val read = ZIO.when(key.isReadable)(socket.doRead)
        val write = ZIO.when(key.isWritable)(socket.doWrite)
        connect *> (read &> write)

      case server: FramedSocketServer => server.doAccept(selector)
      case other => ZIO.dieMessage(s"Selection key with unknown attachment: $other")
    }

    private val runSelect: RIO[Blocking, Unit] = for {
      _         <- ZIO.yieldNow
      nKeys     <- effectBlockingCancelable(selector.select(intervalMillis))(effectTotal(selector.wakeup())).doUntil(_ > 0)
      javaKeys  <- effectTotal(selector.selectedKeys())
      scalaKeys  = javaKeys.asScala.toList
      _         <- ZIO.foreachPar_(scalaKeys) {
        key => handleKey(key).ensuring(effectTotal(javaKeys.remove(key)))
      }
    } yield ()

  }

  object NetworkingService {

    def make(
      selectInterval: Duration = Duration(500, TimeUnit.MILLISECONDS)
    ): ZManaged[Blocking, Throwable, NetworkingService] = for {
      selector <- effect(Selector.open()).toManaged(effectClose)
      service   = new NetworkingService(selector, selectInterval)
      _        <- service.runSelect.forever.forkDaemon.toManaged(_.interrupt)
    } yield service

  }

}
