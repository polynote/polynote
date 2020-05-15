package polynote.server
package repository.fs

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Instant

import polynote.messages.{Message, Notebook}
import scodec.bits.{BitVector, ByteVector}
import scodec.{Attempt, Codec, codecs}
import scodec.stream.decode
import scodec.stream.decode.StreamDecoder
import zio.{RIO, Task, ZIO}
import zio.blocking.Blocking
import zio.clock.{Clock, currentDateTime}

import scala.util.Try

object WAL {

  val WALMagicNumber: Array[Byte] = "PNWAL".getBytes(StandardCharsets.US_ASCII)
  val WALVersion: Short = 1

  // Timestamp for each update is stored in 32 bits unsigned, epoch UTC seconds.
  // So we'll have to change the format by February of 2106. Apologies to my great-great-great grandchildren.
  private val instantCodec = codecs.uint32.exmap[Instant](
    epochSeconds => Attempt.fromTry(Try(Instant.ofEpochSecond(epochSeconds))),
    instant      => Attempt.successful(instant.getEpochSecond)
  )

  def encodeTimestamp(instant: Instant): Task[BitVector] =
    ZIO.fromEither(instantCodec.encode(instant).toEither)
      .mapError(err => new RuntimeException(err.message))

  val messageCodec: Codec[(Instant, Message)] = codecs.variableSizeBytes(codecs.int32, instantCodec ~ Message.codec)

  val decoder: StreamDecoder[(Instant, Message)] = {
    val readMagic = decode.once(codecs.constant(ByteVector(WALMagicNumber)))
    val readVersion = decode.once(codecs.int16)
    def readMessages(version: Int): StreamDecoder[(Instant, Message)] = version match {
      case 1 => decode.many(messageCodec)
      case v => decode.raiseError(new Exception(s"Unknown WAL version $v"))
    }

    for {
      _       <- readMagic
      ver     <- readVersion
      message <- readMessages(ver)
    } yield message
  }

  trait WALWriter {
    protected def append(bytes: Array[Byte]): RIO[Blocking, Unit] = append(ByteBuffer.wrap(bytes))
    protected def append(bytes: BitVector): RIO[Blocking, Unit] = append(bytes.toByteBuffer)
    protected def append(bytes: ByteBuffer): RIO[Blocking, Unit]

    def writeHeader(notebook: Notebook): RIO[Blocking with Clock, Unit] =
      append(WALMagicNumber) *>
        append(BitVector.fromShort(WALVersion)) *>
        appendMessage(notebook.withoutResults)

    def appendMessage(message: Message): RIO[Blocking with Clock, Unit] = for {
      ts    <- currentDateTime.map(_.toInstant)
      bytes <- ZIO.fromEither(messageCodec.encode((ts, message)).toEither).mapError(err => new RuntimeException(err.message))
      _     <- append(bytes)
    } yield ()

    def sync(): RIO[Blocking, Unit]

    def close(): RIO[Blocking, Unit]
  }

  object WALWriter {
    object NoWAL extends WALWriter {
      override protected def append(bytes: ByteBuffer): RIO[Blocking, Unit] = ZIO.unit
      override def sync(): RIO[Blocking, Unit] = ZIO.unit
      override def close(): RIO[Blocking, Unit] = ZIO.unit
    }
  }
}
