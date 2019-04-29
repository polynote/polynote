package polynote.kernel.remote
import polynote.kernel._
import scodec.{Attempt, Codec, Err}
import scodec.codecs.{Discriminated, Discriminator, byte}
import scodec.codecs.implicits._
import polynote.messages._
import polynote.runtime.{StreamingDataRepr, TableOp}
import shapeless.{LabelledGeneric, cachedImplicit}

/*
 * The protocol for communication with remote kernels. Essentially just an ADT version of KernelAPI, with some limitations
 */
sealed trait RemoteRequest {
  def reqId: Int
}

abstract class RemoteRequestCompanion[T](msgTypeId: Byte) {
  implicit val discriminator: Discriminator[RemoteRequest, T, Byte] = Discriminator(msgTypeId)
}

final case class InitialNotebook(reqId: Int, notebook: Notebook) extends RemoteRequest
object InitialNotebook extends RemoteRequestCompanion[InitialNotebook](-1) {
  private implicit val notebookCodec: Codec[Notebook] = Message.codec.exmap(
    _ match {
      case msg: Notebook => Attempt.successful(msg)
      case _ => Attempt.failure(Err("Not a notebook update message"))
    },
    msg => Attempt.successful(msg)
  )
  implicit val codec: Codec[InitialNotebook] = cachedImplicit
}

final case class Shutdown(reqId: Int) extends RemoteRequest
object Shutdown extends RemoteRequestCompanion[Shutdown](1)

final case class StartInterpreterFor(reqId: Int, cell: CellID) extends RemoteRequest
object StartInterpreterFor extends RemoteRequestCompanion[StartInterpreterFor](2) {
  implicit val codec: Codec[StartInterpreterFor] = cachedImplicit
}

final case class QueueCell(reqId: Int, id: CellID) extends RemoteRequest
object QueueCell extends RemoteRequestCompanion[QueueCell](3)

final case class CompletionsAt(reqId: Int, id: CellID, pos: Int) extends RemoteRequest
object CompletionsAt extends RemoteRequestCompanion[CompletionsAt](5)

final case class ParametersAt(reqId: Int, id: CellID, pos: Int) extends RemoteRequest
object ParametersAt extends RemoteRequestCompanion[ParametersAt](6)

final case class CurrentSymbols(reqId: Int) extends RemoteRequest
object CurrentSymbols extends RemoteRequestCompanion[CurrentSymbols](7)

final case class CurrentTasks(reqId: Int) extends RemoteRequest
object CurrentTasks extends RemoteRequestCompanion[CurrentTasks](8)

final case class IdleRequest(reqId: Int) extends RemoteRequest
object IdleRequest extends RemoteRequestCompanion[IdleRequest](9)

final case class InfoRequest(reqId: Int) extends RemoteRequest
object InfoRequest extends RemoteRequestCompanion[InfoRequest](10)

final case class GetHandleDataRequest(reqId: Int, handleType: HandleType, handle: Int, count: Int) extends RemoteRequest
object GetHandleDataRequest extends RemoteRequestCompanion[GetHandleDataRequest](11)

final case class ModifyStreamRequest(reqId: Int, handleId: Int, ops: List[TableOp]) extends RemoteRequest
object ModifyStreamRequest extends RemoteRequestCompanion[ModifyStreamRequest](12) {
  import TableOpCodec.tableOpCodec
  implicit val codec: Codec[ModifyStreamRequest] = cachedImplicit
}

final case class ReleaseHandleRequest(reqId: Int, handleType: HandleType, handleId: Int) extends RemoteRequest
object ReleaseHandleRequest extends RemoteRequestCompanion[ReleaseHandleRequest](13)

final case class CancelTasksRequest(reqId: Int) extends RemoteRequest
object CancelTasksRequest extends RemoteRequestCompanion[CancelTasksRequest](14)

//final case class UpdateNotebookRequest(reqId: Int, version: Int, update: NotebookUpdate) extends RemoteRequest
//object UpdateNotebookRequest extends RemoteRequestCompanion[UpdateNotebookRequest](15) {
//  private implicit val notebookUpdateCodec: Codec[NotebookUpdate] = Message.codec.exmap[NotebookUpdate](
//    _ match {
//      case msg: NotebookUpdate => Attempt.successful(msg)
//      case _ => Attempt.failure(Err("Not a notebook update message"))
//    },
//    msg => Attempt.successful(msg)
//  )
//
//  implicit val codec: Codec[UpdateNotebookRequest] = cachedImplicit
//}

object RemoteRequest {
  implicit val discriminated: Discriminated[RemoteRequest, Byte] = Discriminated(byte)
  implicit val codec: Codec[RemoteRequest] = cachedImplicit
}


sealed trait RemoteResponse
sealed trait RequestResponse extends RemoteResponse {
  def reqId: Int
}

abstract class RemoteResponseCompanion[T <: RemoteResponse](msgTypeId: Byte) {
  implicit val discriminator: Discriminator[RemoteResponse, T, Byte] = Discriminator(msgTypeId)
}

final case class UnitResponse(reqId: Int) extends RequestResponse
object UnitResponse extends RemoteResponseCompanion[UnitResponse](0)

final case class CellQueued(reqId: Int) extends RequestResponse
object CellQueued extends RemoteResponseCompanion[CellQueued](1)

final case class StreamStarted(reqId: Int) extends RequestResponse
object StreamStarted extends RemoteResponseCompanion[StreamStarted](2)

final case class ResultStreamElements(reqId: Int, elements: ShortList[Result]) extends RequestResponse
object ResultStreamElements extends RemoteResponseCompanion[ResultStreamElements](3)

final case class StreamEnded(reqId: Int) extends RequestResponse
object StreamEnded extends RemoteResponseCompanion[StreamEnded](4)

final case class CompletionsResponse(reqId: Int, completions: List[Completion]) extends RequestResponse
object CompletionsResponse extends RemoteResponseCompanion[CompletionsResponse](5)

final case class ParameterHintsResponse(reqId: Int, signatures: Option[Signatures]) extends RequestResponse
object ParameterHintsResponse extends RemoteResponseCompanion[ParameterHintsResponse](6)

final case class CurrentSymbolsResponse(reqId: Int,symbols: List[ResultValue]) extends RequestResponse
object CurrentSymbolsResponse extends RemoteResponseCompanion[CurrentSymbolsResponse](7)

final case class CurrentTasksResponse(reqId: Int,tasks: List[TaskInfo]) extends RequestResponse
object CurrentTasksResponse extends RemoteResponseCompanion[CurrentTasksResponse](8)

final case class IdleResponse(reqId: Int, idle: Boolean) extends RequestResponse
object IdleResponse extends RemoteResponseCompanion[IdleResponse](9)

final case class InfoResponse(reqId: Int, info: Option[KernelInfo]) extends RequestResponse
object InfoResponse extends RemoteResponseCompanion[InfoResponse](10)

final case class HandleDataResponse(reqId: Int, data: Array[ByteVector32]) extends RequestResponse
object HandleDataResponse extends RemoteResponseCompanion[HandleDataResponse](11)

final case class ModifyStreamResponse(reqId: Int, repr: Option[StreamingDataRepr]) extends RequestResponse
object ModifyStreamResponse extends RemoteResponseCompanion[ModifyStreamResponse](12) {
  import ValueReprCodec.streamingDataReprCodec
  implicit val codec: Codec[ModifyStreamResponse] = cachedImplicit
}

final case class Announce(remoteAddress: String) extends RemoteResponse
object Announce extends RemoteResponseCompanion[Announce](-1)

final case class KernelStatusResponse(message: KernelStatusUpdate) extends RemoteResponse
object KernelStatusResponse extends RemoteResponseCompanion[KernelStatusResponse](-2)

object RemoteResponse {
  implicit val discriminated: Discriminated[RemoteResponse, Byte] = Discriminated(byte)
  implicit val codec: Codec[RemoteResponse] = cachedImplicit
}