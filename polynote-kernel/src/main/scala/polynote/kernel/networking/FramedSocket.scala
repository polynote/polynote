package polynote.kernel.networking

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{SelectionKey, Selector, SocketChannel}
import java.util.concurrent.atomic.AtomicBoolean

import zio.{Cause, Exit, Promise, Queue, RIO, RManaged, Ref, Semaphore, Task, TaskManaged, UIO, UManaged, ZIO, ZManaged}
import zio.ZIO.{effect, effectTotal}
import zio.blocking.{Blocking, effectBlocking}
import zio.stream.{Take, ZStream}

class FramedSocket private (
  channel: SocketChannel,
  incoming: Queue[Take[Throwable, ByteBuffer]],
  outgoing: Queue[(ByteBuffer, Promise[Throwable, Unit])],
  currentRead: Ref[Option[ByteBuffer]],
  currentWrite: Ref[Option[(ByteBuffer, Promise[Throwable, Unit])]],
  readLock: Semaphore,
  connected: Promise[Throwable, Unit],
  closed: Promise[Throwable, Unit]
) {
  private val isNotClosed = new AtomicBoolean(true)
  def isOpen: UIO[Boolean] = effectTotal(isNotClosed.get())

  /**
    * A stream of received buffers
    */
  val received: ZStream[Any, Throwable, ByteBuffer] = ZStream.fromQueueWithShutdown(incoming).unTake

  /**
    * Send a buffer through the socket.
    *
    * @param buf The buffer to send. This buffer is now owned by the socket; you mustn't retain a reference to it.
    *            For alternate cleanup behavior, use [[sendManaged]].
    */
  def send(buf: ByteBuffer): Task[Unit] = ZIO.whenM(isOpen) {
    for {
      completer <- Promise.make[Throwable, Unit]
      _          = buf.rewind()
      _         <- outgoing.offer((buf, completer)).doUntilEquals(true)
      _         <- completer.await
    } yield ()
  }

  /**
    * Send a buffer defined by a ZManaged; it will be acquired immediately and freed when it has been sent (or failed)
    */
  def sendManaged[R](managedBuf: ZManaged[R, Throwable, ByteBuffer]): RIO[R, Unit] = managedBuf.use(send)

  private val incomingLengthBuffer = ByteBuffer.allocate(4)
  private val outgoingLengthBuffer = ByteBuffer.allocate(4)

  private def closeFail(err: Throwable): UIO[Unit] = closed.fail(err) *> connected.fail(err) *> close()

  private[networking] val doConnect: UIO[Unit] =
    effectTotal(channel.isConnected).flatMap {
      case true  => connected.succeed(()).unit
      case false => ((ZIO.yieldNow *> effect(channel.finishConnect())).doUntil(identity) *> connected.succeed(())).unit.catchAll(closeFail)
    }

  private[networking] val doRead: UIO[Unit] = {
    lazy val readBytes: Task[Unit] = currentRead.get.flatMap {
      case None =>
        effect(channel.read(incomingLengthBuffer)).flatMap {
          case -1 => incoming.offer(Take.End).unit
          case n if !incomingLengthBuffer.hasRemaining =>
            incomingLengthBuffer.flip()
            val len = incomingLengthBuffer.getInt(0)
            currentRead.set(Some(ByteBuffer.allocate(len)))
            readBytes
          case n => ZIO.unit
        }

      case Some(buf) =>
        effect(channel.read(buf)).flatMap {
          case -1 => incoming.offer(Take.End).unit
          case n if !buf.hasRemaining =>
            buf.flip()
            currentRead.set(None) *> incoming.offer(Take.Value(buf)).unit *> readBytes

          case n => ZIO.unit
        }
    }

    readLock.withPermit(readBytes).catchAll {
      err => incoming.offer(Take.Fail(Cause.fail(err))).unit
    }
  }

  private[networking] lazy val doWrite: UIO[Unit] = {
    def writeLength(tup: (ByteBuffer, Promise[Throwable, Unit])): UIO[Unit] = {
      val (buf, completer) = tup
      for {
        _ <- effectTotal(outgoingLengthBuffer.putInt(0, buf.limit()))
        _ <- effect(channel.write(outgoingLengthBuffer)).doUntil(_ => !outgoingLengthBuffer.hasRemaining).catchAll {
          err => completer.fail(err).unit
        }
      } yield ()
    }

    currentWrite.get.flatMap {
      case None => (outgoing.take.tap(writeLength).asSome >>= currentWrite.set) *> doWrite
      case Some((buf, complete)) =>
        effect(channel.write(buf)).flatMap {
          case _ if !buf.hasRemaining => currentWrite.set(None)
          case _ => ZIO.unit
        }
    }.catchAll(closeFail)
  }

  def close(): UIO[Unit] =
    effectTotal(isNotClosed.set(false)) *>
      (effectTotal(channel.shutdownInput()) &> effectTotal(channel.shutdownOutput())) *>
      incoming.offer(Take.End) *>
      outgoing.takeAll.flatMap(elems => ZIO.foreachPar_(elems)(_._2.interrupt))
      (effectTotal(channel.close()) &> effectTotal(channel.close())) *>
      closed.succeed(()).unit

  def awaitClosed: Task[Unit] = closed.await &> incoming.awaitShutdown &> outgoing.awaitShutdown
  def awaitConnected: Task[Unit] = connected.await

}

object FramedSocket {

  private def make(
    channel: SocketChannel,
    incoming: Queue[Take[Throwable, ByteBuffer]],
    outgoing: Queue[(ByteBuffer, Promise[Throwable, Unit])]
  ): UIO[FramedSocket] = for {
    currentRead  <- Ref.make[Option[ByteBuffer]](None)
    currentWrite <- Ref.make[Option[(ByteBuffer, Promise[Throwable, Unit])]](None)
    readLock     <- Semaphore.make(1L)
    closed       <- Promise.make[Throwable, Unit]
    connected    <- Promise.make[Throwable, Unit]
  } yield new FramedSocket(channel, incoming, outgoing, currentRead, currentWrite, readLock, closed, connected)

  private def make(channel: SocketChannel): UManaged[FramedSocket] = for {
    incoming <- Queue.unbounded[Take[Throwable, ByteBuffer]].toManaged(_.shutdown)
    outgoing <- Queue.unbounded[(ByteBuffer, Promise[Throwable, Unit])].toManaged(_.shutdown)
    socket   <- make(channel, incoming, outgoing).toManaged(_.close())
  } yield socket

  private[networking] def register(channel: SocketChannel, selector: Selector): TaskManaged[FramedSocket] =
    for {
      socket   <- make(channel)
      key      <- effectTotal(channel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE | SelectionKey.OP_CONNECT, socket)).toManaged(key => effectTotal(key.cancel()))
    } yield socket

  private[networking] def client(address: InetSocketAddress, selector: Selector): TaskManaged[FramedSocket] = for {
    channel <- effect(SocketChannel.open()).toManaged(effectClose)
    _        = channel.configureBlocking(false)
    _       <- effect(channel.connect(address)).toManaged_
    socket  <- register(channel, selector).tapM(_.awaitConnected)
  } yield socket

}
