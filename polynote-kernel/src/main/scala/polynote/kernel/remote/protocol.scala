package polynote.kernel.remote

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import polynote.config.PolynoteConfig
import polynote.kernel.{Completion, KernelBusyState, KernelInfo, KernelStatusUpdate, Result, ResultValue, Signatures}
import polynote.messages._
import polynote.runtime.{StreamingDataRepr, TableOp}
import scodec.codecs.{Discriminated, Discriminator, byte}
import scodec.codecs.implicits._
import scodec.{Attempt, Codec, Err, codecs}
import shapeless.{Coproduct, cachedImplicit}
import polynote.kernel.ValueReprCodec.streamingDataReprCodec
import polynote.kernel.TableOpCodec.tableOpCodec
import scodec.bits.BitVector
import zio.{Task, ZIO}

sealed trait IdentifyChannel
case object MainChannel extends IdentifyChannel
case object NotebookUpdatesChannel extends IdentifyChannel
object IdentifyChannel {
  implicit val codec: Codec[IdentifyChannel] = byte.exmap(
    {
      case 0 => Attempt.successful(MainChannel)
      case 1 => Attempt.successful(NotebookUpdatesChannel)
      case n => Attempt.failure(Err(s"Invalid channel type identifier $n"))
    },
    {
      case MainChannel => Attempt.successful(0)
      case NotebookUpdatesChannel => Attempt.successful(1)
    }
  )

  def decodeBuffer(buf: ByteBuffer): Task[IdentifyChannel] = ZIO.fromEither(codec.decode(BitVector(buf)).toEither).mapError(err => new RuntimeException(err.message)).map(_.value)
  def encode(value: IdentifyChannel): Task[BitVector] = ZIO.fromEither(codec.encode(value).toEither).mapError(err => new RuntimeException(err.message))
}

object Update {
  implicit val notebookUpdateCodec: Codec[NotebookUpdate] = NotebookUpdate.codec
}

sealed trait RemoteRequest {
  val reqId: Int
}

abstract class RemoteRequestCompanion[T](msgTypeId: Byte) {
  implicit val discriminator: Discriminator[RemoteRequest, T, Byte] = Discriminator(msgTypeId)
}

final case class StartupRequest(reqId: Int, notebook: Notebook, globalVersion: Int, config: PolynoteConfig) extends RemoteRequest

object StartupRequest extends RemoteRequestCompanion[StartupRequest](1) {
  private implicit val notebookCodec: Codec[Notebook] = Message.codec.exmap(
    _ match {
      case msg: Notebook => Attempt.successful(msg)
      case _ => Attempt.failure(Err("Not a notebook update message"))
    },
    msg => Attempt.successful(msg)
  )

  // we'll just use JSON to encode the polynote config
  private implicit val configCodec: Codec[PolynoteConfig] = scodec.codecs.string32(StandardCharsets.UTF_8).exmap(
    str => PolynoteConfig.parse(str).fold(err => Attempt.failure(Err(err.getMessage)), Attempt.successful),
    config => Attempt.successful(PolynoteConfig.encoder(config).noSpaces)
  )

  implicit val codec: Codec[StartupRequest] = cachedImplicit
}

final case class ShutdownRequest(reqId: Int) extends RemoteRequest
object ShutdownRequest extends RemoteRequestCompanion[ShutdownRequest](2)

final case class QueueCellRequest(reqId: Int, id: CellID) extends RemoteRequest
object QueueCellRequest extends RemoteRequestCompanion[QueueCellRequest](3)

final case class CompletionsAtRequest(reqId: Int, id: CellID, pos: Int) extends RemoteRequest
object CompletionsAtRequest extends RemoteRequestCompanion[CompletionsAtRequest](4)

final case class ParametersAtRequest(reqId: Int, id: CellID, pos: Int) extends RemoteRequest
object ParametersAtRequest extends RemoteRequestCompanion[ParametersAtRequest](5)

final case class StatusRequest(reqId: Int) extends RemoteRequest
object StatusRequest extends RemoteRequestCompanion[StatusRequest](6)

final case class ValuesRequest(reqId: Int) extends RemoteRequest
object ValuesRequest extends RemoteRequestCompanion[ValuesRequest](7)

final case class GetHandleDataRequest(reqId: Int, sessionId: Int, handleType: HandleType, handle: Int, count: Int) extends RemoteRequest
object GetHandleDataRequest extends RemoteRequestCompanion[GetHandleDataRequest](8)

final case class ModifyStreamRequest(reqId: Int, sessionId: Int, handleId: Int, ops: List[TableOp]) extends RemoteRequest
object ModifyStreamRequest extends RemoteRequestCompanion[ModifyStreamRequest](9)

final case class ReleaseHandleRequest(reqId: Int, sessionId: Int, handleType: HandleType, handleId: Int) extends RemoteRequest
object ReleaseHandleRequest extends RemoteRequestCompanion[ReleaseHandleRequest](10)

final case class CancelAllRequest(reqId: Int) extends RemoteRequest
object CancelAllRequest extends RemoteRequestCompanion[CancelAllRequest](12)

final case class KernelInfoRequest(reqId: Int) extends RemoteRequest
object KernelInfoRequest extends RemoteRequestCompanion[KernelInfoRequest](13)

object RemoteRequest {
  implicit val discriminated: Discriminated[RemoteRequest, Byte] = Discriminated(byte)
  implicit val codec: Codec[RemoteRequest] = cachedImplicit
}

sealed trait RemoteResponse
sealed trait RemoteRequestResponse extends RemoteResponse {
  def reqId: Int
}

abstract class RemoteResponseCompanion[T <: RemoteResponse](msgTypeId: Byte) {
  implicit val discriminator: Discriminator[RemoteResponse, T, Byte] = Discriminator(msgTypeId)
}

//
// These first few aren't a direct response to a particular type of request, so their type ID is negative in order to
// leave the positive space free to for responses to a particular request type to have a matching ID.
//
final case class UnitResponse(reqId: Int) extends RemoteRequestResponse
object UnitResponse extends RemoteResponseCompanion[UnitResponse](-1)

final case class ResultResponse(reqId: Int, result: Result) extends RemoteRequestResponse
object ResultResponse extends RemoteResponseCompanion[ResultResponse](-2)

final case class ResultsResponse(reqId: Int, results: List[Result]) extends RemoteRequestResponse
object ResultsResponse extends RemoteResponseCompanion[ResultsResponse](-3)

final case class KernelStatusResponse(status: KernelStatusUpdate) extends RemoteResponse
object KernelStatusResponse extends RemoteResponseCompanion[KernelStatusResponse](-4)

// The rest are responses to a particular request type
final case class Announce(reqId: Int, remoteAddress: String) extends RemoteRequestResponse
object Announce extends RemoteResponseCompanion[Announce](0)

final case class ShutdownResponse(reqId: Int) extends RemoteRequestResponse
object ShutdownResponse extends RemoteResponseCompanion[ShutdownResponse](2)

final case class RunCompleteResponse(reqId: Int) extends RemoteRequestResponse
object RunCompleteResponse extends RemoteResponseCompanion[RunCompleteResponse](3)

final case class CompletionsAtResponse(reqId: Int, completions: List[Completion]) extends RemoteRequestResponse
object CompletionsAtResponse extends RemoteResponseCompanion[CompletionsAtResponse](4)

final case class ParametersAtResponse(reqId: Int, signatures: Option[Signatures]) extends RemoteRequestResponse
object ParametersAtResponse extends RemoteResponseCompanion[ParametersAtResponse](5)

final case class StatusResponse(reqId: Int, status: KernelBusyState) extends RemoteRequestResponse
object StatusResponse extends RemoteResponseCompanion[StatusResponse](6)

final case class ValuesResponse(reqId: Int, values: List[ResultValue]) extends RemoteRequestResponse
object ValuesResponse extends RemoteResponseCompanion[ValuesResponse](7)

final case class GetHandleDataResponse(reqId: Int, data: Array[ByteVector32]) extends RemoteRequestResponse
object GetHandleDataResponse extends RemoteResponseCompanion[GetHandleDataResponse](8)

final case class ModifyStreamResponse(reqId: Int, result: Option[StreamingDataRepr]) extends RemoteRequestResponse
object ModifyStreamResponse extends RemoteResponseCompanion[ModifyStreamResponse](9)

final case class KernelInfoResponse(reqId: Int, info: KernelInfo) extends RemoteRequestResponse
object KernelInfoResponse extends RemoteResponseCompanion[KernelInfoResponse](13)

object RemoteResponse {
  implicit val discriminated: Discriminated[RemoteResponse, Byte] = Discriminated(byte)
  implicit val codec: Codec[RemoteResponse] = cachedImplicit
}

