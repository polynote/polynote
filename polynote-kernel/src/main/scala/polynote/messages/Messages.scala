package polynote.messages

import cats.MonadError
import cats.syntax.either._
import io.circe.{Decoder, Encoder}
import polynote.kernel._
import scodec.Codec
import scodec.bits.BitVector
import scodec.codecs._
import scodec.codecs.implicits._
import io.circe.generic.semiauto._
import polynote.config.{DependencyConfigs, RepositoryConfig}
import polynote.data.Rope

sealed trait Message

object Message {
  implicit val discriminated: Discriminated[Message, Byte] = Discriminated(byte)

  val codec: Codec[Message] = Codec[Message]

  def decode[F[_]](bytes: Array[Byte])(implicit F: MonadError[F, Throwable]): F[Message] = F.fromEither {
    codec.decode(BitVector(bytes)).toEither
      .map(_.value)
      .leftMap {
        err => new Exception(err.messageWithContext)
      }
  }

  def encode[F[_]](msg: Message)(implicit F: MonadError[F, Throwable]): F[BitVector] = F.fromEither {
    codec.encode(msg).toEither.map(_.value).leftMap {
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
  hideOutput: Boolean = false
)

final case class NotebookCell(
  id: TinyString,
  language: TinyString,
  content: Rope,
  results: ShortList[Result] = ShortList(Nil),
  metadata: CellMetadata = CellMetadata()
) {
  def updateContent(fn: Rope => Rope): NotebookCell = copy(content = fn(content))
}

object NotebookCell {
  def apply(id: TinyString, language: TinyString, content: String): NotebookCell = NotebookCell(id, language, Rope(content))
}

final case class NotebookConfig(
  dependencies: Option[DependencyConfigs],
  repositories: Option[TinyList[RepositoryConfig]]
)

object NotebookConfig {
  implicit val encoder: Encoder[NotebookConfig] = deriveEncoder[NotebookConfig]
  implicit val decoder: Decoder[NotebookConfig] = deriveDecoder[NotebookConfig]
}

final case class Notebook(path: ShortString, cells: ShortList[NotebookCell], config: Option[NotebookConfig]) extends Message {
  def map(fn: NotebookCell => NotebookCell): Notebook = copy(
    cells = ShortList(cells.map(fn))
  )

  def updateCell(id: TinyString)(fn: NotebookCell => NotebookCell): Notebook = map {
    case cell if cell.id == id => fn(cell)
    case cell => cell
  }

  def addCell(cell: NotebookCell): Notebook = copy(cells = ShortList(cells :+ cell))

  def setResults(id: String, results: List[Result]): Notebook = updateCell(TinyString(id)) {
    cell => cell.copy(results = ShortList(results))
  }
}
object Notebook extends MessageCompanion[Notebook](2)

final case class RunCell(notebook: ShortString, id: ShortList[TinyString]) extends Message
object RunCell extends MessageCompanion[RunCell](3)

final case class CellResult(notebook: ShortString, id: TinyString, result: Result) extends Message
object CellResult extends MessageCompanion[CellResult](4)

final case class ContentEdit(pos: Int, length: Int, content: String) {
  def applyTo(rope: Rope): Rope = if (length > 0) rope.delete(pos + 1, length).insertAt(pos, Rope(content)) else rope.insertAt(pos, Rope(content))
}

sealed trait NotebookUpdate

final case class UpdateCell(notebook: ShortString, id: TinyString, content: ShortList[ContentEdit]) extends Message with NotebookUpdate
object UpdateCell extends MessageCompanion[UpdateCell](5)

final case class InsertCell(notebook: ShortString, cell: NotebookCell, after: Option[TinyString]) extends Message with NotebookUpdate
object InsertCell extends MessageCompanion[InsertCell](6)

final case class CompletionsAt(notebook: ShortString, id: TinyString, pos: Int, completions: ShortList[Completion]) extends Message
object CompletionsAt extends MessageCompanion[CompletionsAt](7)

final case class ParametersAt(notebook: ShortString, id: TinyString, pos: Int, signatures: Option[Signatures]) extends Message
object ParametersAt extends MessageCompanion[ParametersAt](8)

final case class KernelStatus(notebook: ShortString, update: KernelStatusUpdate) extends Message
object KernelStatus extends MessageCompanion[KernelStatus](9)

final case class UpdateConfig(notebook: ShortString, config: NotebookConfig) extends Message with NotebookUpdate
object UpdateConfig extends MessageCompanion[UpdateConfig](10)

final case class SetCellLanguage(notebook: ShortString, id: TinyString, language: TinyString) extends Message
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

final case class DeleteCell(notebook: ShortString, id: TinyString) extends Message with NotebookUpdate
object DeleteCell extends MessageCompanion[DeleteCell](15)