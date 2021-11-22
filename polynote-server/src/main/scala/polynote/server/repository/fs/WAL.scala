package polynote.server
package repository.fs

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Instant
import polynote.messages.{Message, Notebook}
import polynote.kernel.CodecError
import scodec.bits.{BitVector, ByteVector}
import scodec.{Attempt, Codec, DecodeResult, codecs}
import zio.{RIO, Ref, Task, ZIO, ZManaged}
import zio.blocking.{Blocking, effectBlocking}
import zio.clock.{Clock, currentDateTime}
import zio.stream.{Take, ZStream}

import java.nio.channels.FileChannel
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
  private def decodeMessage(bits: BitVector): RIO[Blocking, DecodeResult[(Instant, Message)]] =
    effectBlocking(messageCodec.decode(bits).toEither.left.map(err => CodecError(err))).absolve

  private val headerCodec = codecs.constant(ByteVector(WALMagicNumber)) ~> codecs.int16
  private def decodeHeader(bits: BitVector): RIO[Blocking, DecodeResult[Int]] =
    effectBlocking(headerCodec.decode(bits).toEither.left.map(err => CodecError(err))).absolve

  def decode[R](is: ZManaged[R, Nothing, FileChannel]): ZStream[Blocking with R, Throwable, (Instant, Message)] = for {
    bits   <- ZStream.managed(is).mapM(in => effectBlocking(BitVector.fromMmap(in)))
    header <- ZStream.fromEffect(decodeHeader(bits))
    _      <- ZStream.when(header.value != 1)(ZStream.fail(new IllegalStateException(s"Unknown WAL version ${header.value}")))
    remain <- ZStream.fromEffect(Ref.make(header.remainder))
    messages <- ZStream.repeatEffect {
      remain.get.flatMap {
        case done if done.isEmpty => ZIO.succeed(Take.end)
        case bits => decodeMessage(bits).flatMap {
          result => remain.set(result.remainder).as(Take.single(result.value))
        }
      }
    }.flattenTake
  } yield messages

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
