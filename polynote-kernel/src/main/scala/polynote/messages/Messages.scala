package polynote.messages

import cats.MonadError
import cats.syntax.either._
import io.circe.{Decoder, Encoder}
import polynote.kernel._
import scodec.Codec
import scodec.bits.{BitVector, ByteVector}
import scodec.codecs._
import scodec.codecs.implicits._
import io.circe.generic.semiauto._
import polynote.config.{DependencyConfigs, RepositoryConfig}
import polynote.data.Rope
import polynote.runtime.{StreamingDataRepr, TableOp}

import scala.collection.mutable.{ArrayBuffer, ListBuffer}

sealed trait Message

object Message {
  implicit val discriminated: Discriminated[Message, Byte] = Discriminated(byte)

  val codec: Codec[Message] = Codec[Message]

  def decode[F[_]](bytes: ByteVector)(implicit F: MonadError[F, Throwable]): F[Message] = F.fromEither {
    codec.decode(bytes.toBitVector).toEither
      .map(_.value)
      .leftMap {
        err => new Exception(err.messageWithContext)
      }
  }

  def encode[F[_]](msg: Message)(implicit F: MonadError[F, Throwable]): F[BitVector] = F.fromEither {
    codec.encode(msg).toEither.leftMap {
      err => new Exception(err.messageWithContext)
    }
  }
}

abstract class MessageCompanion[T](msgId: Byte) {
  implicit final val discriminator: Discriminator[Message, T, Byte] = Discriminator(msgId)
}

final case class Error(code: Int, error: Throwable) extends Message

object Error extends MessageCompanion[Error](0) {
  implicit val codec: Codec[Error] = (uint16 ~ RuntimeError.throwableWithCausesCodec).xmap(
    t => Error(t._1, t._2),
    e => (e.code, e.error)
  )
}

final case class LoadNotebook(path: ShortString) extends Message
object LoadNotebook extends MessageCompanion[LoadNotebook](1)

final case class CellMetadata(
  disableRun: Boolean = false,
  hideSource: Boolean = false,
  hideOutput: Boolean = false,
  executionInfo: Option[ExecutionInfo] = None
)

object CellMetadata {
  implicit val encoder: Encoder[CellMetadata] = deriveEncoder[CellMetadata]
  implicit val decoder: Decoder[CellMetadata] = deriveDecoder[CellMetadata]
}

final case class NotebookCell(
  id: CellID,
  language: TinyString,
  content: Rope,
  results: ShortList[Result] = ShortList(Nil),
  metadata: CellMetadata = CellMetadata()
) {
  def updateContent(fn: Rope => Rope): NotebookCell = copy(content = fn(content))
}

object NotebookCell {
  def apply(id: CellID, language: TinyString, content: String): NotebookCell = NotebookCell(id, language, Rope(content))
}

final case class NotebookConfig(
  dependencies: Option[DependencyConfigs],
  exclusions: Option[TinyList[TinyString]],
  repositories: Option[TinyList[RepositoryConfig]],
  sparkConfig: Option[ShortMap[String, String]]
)

object NotebookConfig {
  implicit val encoder: Encoder[NotebookConfig] = deriveEncoder[NotebookConfig]
  implicit val decoder: Decoder[NotebookConfig] = deriveDecoder[NotebookConfig]

  def empty = NotebookConfig(None, None, None, None)
}

final case class Notebook(path: ShortString, cells: ShortList[NotebookCell], config: Option[NotebookConfig]) extends Message {
  def map(fn: NotebookCell => NotebookCell): Notebook = copy(
    cells = ShortList(cells.map(fn))
  )

  def updateCell(id: CellID)(fn: NotebookCell => NotebookCell): Notebook = map {
    case cell if cell.id == id => fn(cell)
    case cell => cell
  }

  def editCell(id: CellID, edits: ContentEdits): Notebook = updateCell(id) {
    cell => cell.updateContent(_.withEdits(edits))
  }

  def addCell(cell: NotebookCell): Notebook = copy(cells = ShortList(cells :+ cell))

  def insertCell(cell: NotebookCell, after: Short): Notebook = {
    require(!cells.exists(_.id == cell.id), s"Cell ids must be unique, a cell with id ${cell.id} already exists")

    val insertIndex = cells.indexWhere(_.id == after)

    copy(
      cells = ShortList(
        cells.take(insertIndex + 1) ++ (cell :: cells.drop(insertIndex + 1))))
  }

  def deleteCell(id: CellID): Notebook = copy(cells = ShortList(cells.collect {
    case cell if cell.id != id => cell
  }))

  def setResults(id: CellID, results: List[Result]): Notebook = updateCell(id) {
    cell => cell.copy(results = ShortList(results))
  }

  def setMetadata(id: CellID, metadata: CellMetadata): Notebook = updateCell(id) {
    cell => cell.copy(metadata = metadata)
  }

  def getCell(id: CellID): Option[NotebookCell] = cells.find(_.id == id)

  def cell(id: CellID): NotebookCell = getCell(id).getOrElse(throw new NoSuchElementException(s"Cell $id does not exist"))
}

object Notebook extends MessageCompanion[Notebook](2)

final case class RunCell(notebook: ShortString, ids: ShortList[CellID]) extends Message
object RunCell extends MessageCompanion[RunCell](3)

final case class CellResult(notebook: ShortString, id: CellID, result: Result) extends Message
object CellResult extends MessageCompanion[CellResult](4)

sealed trait NotebookUpdate extends Message {
  def globalVersion: Int
  def localVersion: Int
  def notebook: ShortString

  def withVersions(global: Int, local: Int): NotebookUpdate = this match {
    case u @ UpdateCell(_, _, _, _, _) => u.copy(globalVersion = global, localVersion = local)
    case i @ InsertCell(_, _, _, _, _) => i.copy(globalVersion = global, localVersion = local)
    case d @ DeleteCell(_, _, _, _)    => d.copy(globalVersion = global, localVersion = local)
    case u @ UpdateConfig(_, _, _, _)  => u.copy(globalVersion = global, localVersion = local)
    case l @ SetCellLanguage(_, _, _, _, _) => l.copy(globalVersion = global, localVersion = local)
  }

  // transform this update so that it has the same effect when applied after the given update
  def rebase(prev: NotebookUpdate): NotebookUpdate = (this, prev) match {
    case (i@InsertCell(_, _, _, cell1, after1), InsertCell(_, _, _, cell2, after2)) if after1 == after2 =>
      // we both tried to insert a cell after the same cell. Transform the first update so it inserts after the cell created by the second update.
      i.copy(after = cell2.id)

    case (u@UpdateCell(_, _, _, id1, edits1), UpdateCell(_, _, _, id2, edits2)) if id1 == id2 =>
      // we both tried to edit the same cell. Transform first edits so they apply to the document state as it exists after the second edits are already applied.

      u.copy(edits = edits1.rebase(edits2))

    // all other cases should be independent (TODO: they're not yet, though)
    case _ => this

  }

  def applyTo(notebook: Notebook): Notebook = this match {
    case InsertCell(_, _, _, cell, after) => notebook.insertCell(cell, after)
    case DeleteCell(_, _, _, id)          => notebook.deleteCell(id)
    case UpdateCell(_, _, _, id, edits)   => notebook.editCell(id, edits)
    case UpdateConfig(_, _, _, config)    => notebook.copy(config = Some(config))
    case SetCellLanguage(_, _, _, id, lang) => notebook.updateCell(id)(_.copy(language = lang))
  }
}

object NotebookUpdate {
  def unapply(message: Message): Option[NotebookUpdate] = message match {
    case msg: NotebookUpdate => Some(msg)
    case _ => None
  }
}

final case class UpdateCell(notebook: ShortString, globalVersion: Int, localVersion: Int, id: CellID, edits: ContentEdits) extends Message with NotebookUpdate
object UpdateCell extends MessageCompanion[UpdateCell](5)

final case class InsertCell(notebook: ShortString, globalVersion: Int, localVersion: Int, cell: NotebookCell, after: CellID) extends Message with NotebookUpdate
object InsertCell extends MessageCompanion[InsertCell](6)

final case class CompletionsAt(notebook: ShortString, id: CellID, pos: Int, completions: ShortList[Completion]) extends Message
object CompletionsAt extends MessageCompanion[CompletionsAt](7)

final case class ParametersAt(notebook: ShortString, id: CellID, pos: Int, signatures: Option[Signatures]) extends Message
object ParametersAt extends MessageCompanion[ParametersAt](8)

final case class KernelStatus(notebook: ShortString, update: KernelStatusUpdate) extends Message
object KernelStatus extends MessageCompanion[KernelStatus](9)

final case class UpdateConfig(notebook: ShortString, globalVersion: Int, localVersion: Int, config: NotebookConfig) extends Message with NotebookUpdate
object UpdateConfig extends MessageCompanion[UpdateConfig](10)

final case class SetCellLanguage(notebook: ShortString, globalVersion: Int, localVersion: Int, id: CellID, language: TinyString) extends Message with NotebookUpdate
object SetCellLanguage extends MessageCompanion[SetCellLanguage](11)

final case class StartKernel(notebook: ShortString, level: Byte) extends Message
object StartKernel extends MessageCompanion[StartKernel](12) {
  // TODO: should probably make this an enum that codecs to a byte, but don't want to futz with that right now
  final val NoRestart = 0.toByte
  final val WarmRestart = 1.toByte
  final val ColdRestart = 2.toByte
  final val Kill = 3.toByte
}

final case class ListNotebooks(paths: List[ShortString]) extends Message
object ListNotebooks extends MessageCompanion[ListNotebooks](13)

final case class CreateNotebook(path: ShortString) extends Message
object CreateNotebook extends MessageCompanion[CreateNotebook](14)

final case class DeleteCell(notebook: ShortString, globalVersion: Int, localVersion: Int, id: CellID) extends Message with NotebookUpdate
object DeleteCell extends MessageCompanion[DeleteCell](15)

final case class ServerHandshake(
  interpreters: TinyMap[TinyString, TinyString]
) extends Message
object ServerHandshake extends MessageCompanion[ServerHandshake](16)

final case class CancelTasks(path: ShortString) extends Message
object CancelTasks extends MessageCompanion[CancelTasks](18)

/*****************************************
 ** Stuff for stream-ish value handling **
 *****************************************/

case object Lazy extends HandleType
case object Updating extends HandleType
case object Streaming extends HandleType

sealed trait HandleType

object HandleType {
  implicit val codec: Codec[HandleType] = discriminated[HandleType].by(byte)
    .|(0) { case Lazy => Lazy } (identity) (provide(Lazy))
    .|(1) { case Updating => Updating } (identity) (provide(Updating))
    .|(2) { case Streaming => Streaming } (identity) (provide(Streaming))
}


final case class HandleData(
  path: ShortString,
  handleType: HandleType,
  handle: Int,
  count: Int,
  data: Array[ByteVector32]
) extends Message

object HandleData extends MessageCompanion[HandleData](17)

/********************************************************
 ** Specifically for streams of structs (i.e. tables)  **
 *******************************************************/

final case class ModifyStream(path: ShortString, fromHandle: Int, ops: TinyList[TableOp], newRepr: Option[StreamingDataRepr]) extends Message
object ModifyStream extends MessageCompanion[ModifyStream](19) {
  import TableOpCodec.tableOpCodec
  import ValueReprCodec.streamingDataReprCodec
  implicit val codec: Codec[ModifyStream] = shapeless.cachedImplicit
}

final case class ReleaseHandle(path: ShortString, handleType: HandleType, handle: Int) extends Message
object ReleaseHandle extends MessageCompanion[ReleaseHandle](20)