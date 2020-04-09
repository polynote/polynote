package polynote.kernel.networking

import java.net.{BindException, InetSocketAddress}
import java.nio.channels.{SelectionKey, Selector, ServerSocketChannel}
import java.util.concurrent.ConcurrentLinkedDeque

import scala.collection.JavaConverters._
import zio.blocking.{Blocking, effectBlocking}
import zio.{Promise, Queue, RIO, RManaged, Task, TaskManaged, UIO, ZIO, ZManaged}
import zio.ZIO.{effect, effectTotal}
import zio.clock.Clock
import zio.duration.Duration

class FramedSocketServer private (
  socket: ServerSocketChannel,
  closed: Promise[Throwable, Unit],
  clients: Queue[TaskManaged[FramedSocket]]
) {

  def acceptM: UIO[TaskManaged[FramedSocket]] = clients.take
  def accept: TaskManaged[FramedSocket] = acceptM.toManaged_.flatten
  def acceptTimeout(timeout: Duration): RManaged[Clock, Option[FramedSocket]] = acceptM.timeout(timeout).toManaged_.flatMap {
    case None => ZManaged.succeed(None)
    case Some(s) => s.asSome
  }

  def serverAddress: Task[InetSocketAddress] =
    effect(socket.getLocalAddress).flatMap(sa => effect(sa.asInstanceOf[InetSocketAddress]))

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
        val managedClient = managedSocket.flatMap {
          socket => effectTotal(openSockets.add(socket))
            .as(socket)
            .toManaged(s => effectTotal(openSockets.remove(s)))
            .tapM {
              socket => socket.doConnect
            }
        }
        clients.offer(managedClient)
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
    clients <- Queue.unbounded[TaskManaged[FramedSocket]]
  } yield new FramedSocketServer(serverSocketChannel, closed, clients)

  private def openChannel: TaskManaged[ServerSocketChannel] =
    effect(ServerSocketChannel.open()).tap(c => effect(c.configureBlocking(false))).toManaged(effectClose)

  private[networking] def open(address: InetSocketAddress, selector: Selector): ZManaged[Any, Throwable, FramedSocketServer] = for {
    serverSocket <- openChannel
    server       <- make(serverSocket).toManaged(_.close())
    _            <- effect(serverSocket.register(selector, SelectionKey.OP_ACCEPT, server)).toManaged(k => effectTotal(k.cancel()))
    _            <- effect(serverSocket.bind(address)).toManaged_
  } yield server

  private[networking] def open(host: String, portRange: Range, selector: Selector): ZManaged[Blocking, Throwable, FramedSocketServer] =
    openChannel.flatMap {
      serverSocket =>
        def bindTo(port: Int): RIO[Blocking, Unit] =
          effectBlocking(serverSocket.bind(new InetSocketAddress(host, port))).unit

        val bindPort = ZIO.firstSuccessOf(bindTo(portRange.head), portRange.tail.map(bindTo))
          .orElseFail(new BindException(s"Unable to bind to any port in range ${portRange.start}-${portRange.end} on $host"))

        for {
          server       <- make(serverSocket).toManaged(_.close())
          _            <- effectTotal(serverSocket.register(selector, SelectionKey.OP_ACCEPT, server)).toManaged(k => effectTotal(k.cancel()))
          _            <- bindPort.toManaged_
        } yield server
  }

}
