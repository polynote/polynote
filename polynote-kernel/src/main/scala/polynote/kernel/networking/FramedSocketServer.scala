package polynote.kernel.networking

import java.net.InetSocketAddress
import java.nio.channels.{SelectionKey, Selector, ServerSocketChannel}
import java.util.concurrent.ConcurrentLinkedDeque

import scala.collection.JavaConverters._
import zio.blocking.{Blocking, effectBlocking}
import zio.{Promise, Queue, RIO, Task, UIO, ZIO, ZManaged}
import zio.ZIO.{effect, effectTotal}

class FramedSocketServer private (
  socket: ServerSocketChannel,
  closed: Promise[Throwable, Unit],
  clients: Queue[FramedSocket]
) {

  def accept: UIO[FramedSocket] = clients.take

  def close(): UIO[Unit] =
    ZIO.foreachPar_(openSockets.asScala)(_.close()).ensuring(clients.shutdown).ensuring(closed.succeed(()).unit)

  def awaitClosed: Task[Unit] = closed.await

  private val openSockets = new ConcurrentLinkedDeque[FramedSocket]()

  private[networking] def doAccept(selector: Selector): UIO[Unit] = {
    val acceptNext = effect(Option(socket.accept())).someOrFail(FramedSocketServer.NoMoreSockets).map {
      channel =>
        for {
          channel <- ZIO.succeed(channel)
            .tap(c => effect(c.configureBlocking(false)))
            .toManaged(effectClose)
          framed  <- FramedSocket.register(channel, selector)
        } yield framed
    }

    acceptNext.flatMap {
      managedSocket =>
        managedSocket.use {
          framedSocket =>
            openSockets.add(framedSocket)
            (clients.offer(framedSocket) *> framedSocket.doConnect *> framedSocket.awaitClosed).ensuring(effectTotal(openSockets.remove(framedSocket)))
        }.forkDaemon
    }.forever.catchSome {
      case FramedSocketServer.NoMoreSockets => ZIO.unit
    }.catchAll {
      err => closed.fail(err) *> close()
    }
  }

}

object FramedSocketServer {
  private object NoMoreSockets extends Throwable

  private def make(serverSocketChannel: ServerSocketChannel): UIO[FramedSocketServer] = for {
    closed  <- Promise.make[Throwable, Unit]
    clients <- Queue.unbounded[FramedSocket]
  } yield new FramedSocketServer(serverSocketChannel, closed, clients)

  private[networking] def open(address: InetSocketAddress, selector: Selector): ZManaged[Any, Throwable, FramedSocketServer] = for {
    serverSocket <- effect(ServerSocketChannel.open()).toManaged(effectClose)
    server       <- make(serverSocket).toManaged(_.close())
    _            <- effectTotal(serverSocket.register(selector, SelectionKey.OP_ACCEPT)).toManaged(k => effectTotal(k.cancel()))
    _            <- effect(serverSocket.bind(address)).toManaged_
  } yield server

}
