package polynote.kernel.networking

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{SelectableChannel, SelectionKey, Selector, SocketChannel}
import java.util.concurrent.atomic.AtomicBoolean

import polynote.kernel.remote.RemoteRequest
import scodec.bits.BitVector
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
  writeLock: Semaphore,
  connected: Promise[Throwable, Unit],
  closed: Promise[Throwable, Unit]
) {
  private val isNotClosed = new AtomicBoolean(true)
  def isOpen: UIO[Boolean] = effectTotal(isNotClosed.get())
  def isConnected: Boolean = channel.isConnected

  /**
    * Take the next buffer (it will not be present in the [[received]] stream, and may race with the stream if it is
    * being consumed)
    */
  val takeNext: UIO[Option[ByteBuffer]] = incoming.take.map {
    case Take.Value(buf) => Some(buf)
    case _ => None
  }

  /**
    * A stream of received buffers
    */
  lazy val received: ZStream[Any, Throwable, ByteBuffer] = ZStream.fromQueueWithShutdown(incoming).unTake.catchAllCause {
    case cause if cause.interruptedOnly => ZStream.empty
    case cause => ZStream.halt(cause)
  }

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
      _         <- outgoing.offer((buf, completer))
      _         <- doWrite
      _         <- completer.await
    } yield ()
  }

  def send(bitVector: BitVector): Task[Unit] = send(bitVector.toByteBuffer)

  /**
    * Send a buffer defined by a ZManaged; it will be acquired immediately and freed when it has been sent (or failed)
    */
  def sendManaged[R](managedBuf: ZManaged[R, Throwable, ByteBuffer]): RIO[R, Unit] = managedBuf.use(send)

  private val incomingLengthBuffer = ByteBuffer.allocate(4)
  private val outgoingLengthBuffer = ByteBuffer.allocate(4)

  private def closeFail(err: Throwable): UIO[Unit] =
    closed.fail(err) *> connected.fail(err) *> close()

  private[networking] val doConnect: UIO[Unit] =
    effectTotal(channel.isConnected).flatMap {
      case true  => connected.succeed(()).unit
      case false => ((ZIO.yieldNow *> effect(channel.finishConnect())).doUntil(identity) *> connected.succeed(())).unit.catchAll(closeFail)
    }

  private[networking] val doRead: UIO[Unit] = {
    lazy val readBytes: Task[Unit] = currentRead.get.flatMap {
      case None =>
        effect(incomingLengthBuffer.rewind()) *> effect(channel.read(incomingLengthBuffer)).flatMap {
          case -1 => incoming.offer(Take.End).unit
          case n if !incomingLengthBuffer.hasRemaining =>
            incomingLengthBuffer.flip()
            val len = incomingLengthBuffer.getInt(0)
            currentRead.set(Some(ByteBuffer.allocate(len))) *> readBytes
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

  private def writeImpl(buf: ByteBuffer, complete: Promise[Throwable, Unit]) =
    effect(channel.write(buf)).onError(err => complete.halt(err) *> currentWrite.set(None)).flatMap {
      case _ if !buf.hasRemaining => currentWrite.set(None) *> complete.succeed(()).unit
      case _ => ZIO.unit
    }

  private[networking] lazy val doWrite: UIO[Unit] = {
    def writeLength(buf: ByteBuffer, completer: Promise[Throwable, Unit]): Task[Unit] = {
      for {
        _ <- effectTotal(outgoingLengthBuffer.putInt(0, buf.limit()))
        _ = outgoingLengthBuffer.rewind()
        _ <- effect(channel.write(outgoingLengthBuffer)).doUntil(_ => !outgoingLengthBuffer.hasRemaining).tapError {
          err => completer.fail(err).unit
        }
      } yield ()
    }

    lazy val writeNext: UIO[Unit] = currentWrite.get.flatMap {
      case None =>
        outgoing.poll.flatMap {
          case some@Some((buf, completer)) => currentWrite.set(some) *> writeLength(buf, completer) <* writeImpl(buf, completer)
          case None                        => ZIO.unit
        }
      case Some((buf, complete)) => writeImpl(buf, complete)
    }.catchAll(closeFail)

    writeLock.withPermit(writeNext)
  }

  private def shutdownChannelIO() = effect {
    if (channel.isOpen) {
      channel.shutdownInput()
      channel.shutdownOutput()
    }
  }.ignore

  def close(): UIO[Unit] = {
    effectTotal(isNotClosed.set(false)) *>
      shutdownChannelIO() *>
      ZIO.whenM(incoming.isShutdown.map(!_))(incoming.offer(Take.End)) *>
      outgoing.takeAll.flatMap(elems => ZIO.foreachPar_(elems)(_._2.interrupt)) *>
      outgoing.shutdown *>
      effectTotal(channel.close()) *>
      closed.succeed(()).unit
  }

  def awaitClosed: Task[Unit] = closed.await
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
    writeLock    <- Semaphore.make(1L)
    closed       <- Promise.make[Throwable, Unit]
    connected    <- Promise.make[Throwable, Unit]
  } yield new FramedSocket(channel, incoming, outgoing, currentRead, currentWrite, readLock, writeLock, closed, connected)

  private def make(channel: SocketChannel): UManaged[FramedSocket] = for {
    incoming <- Queue.unbounded[Take[Throwable, ByteBuffer]].toManaged(_.shutdown)
    outgoing <- Queue.unbounded[(ByteBuffer, Promise[Throwable, Unit])].toManaged(_.shutdown)
    socket   <- make(channel, incoming, outgoing).toManaged(_.close())
  } yield socket

  private def registerChannel(channel: SocketChannel, selector: Selector, socket: FramedSocket): UManaged[SelectionKey] = effectTotal {
    selector.wakeup()
    channel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE | SelectionKey.OP_CONNECT, socket)
  }.toManaged(key => effectTotal(key.cancel()))

  private[networking] def register(channel: SocketChannel, selector: Selector): TaskManaged[FramedSocket] =
    for {
      socket <- make(channel)
      _      <- registerChannel(channel, selector, socket)
    } yield socket

  private[networking] def client(address: InetSocketAddress, selector: Selector): TaskManaged[FramedSocket] = for {
    channel <- effect(SocketChannel.open()).toManaged(effectClose)
    _        = channel.configureBlocking(false)
    _       <- effect(channel.connect(address)).toManaged_
    socket  <- register(channel, selector)
  } yield socket

}
