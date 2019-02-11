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

import scala.collection.mutable.{ArrayBuffer, ListBuffer}

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

  def updateCell(id: String)(fn: NotebookCell => NotebookCell): Notebook = map {
    case cell if cell.id == id => fn(cell)
    case cell => cell
  }

  def editCell(id: String, edits: ContentEdits): Notebook = updateCell(id) {
    cell => cell.updateContent(_.withEdits(edits))
  }

  def addCell(cell: NotebookCell): Notebook = copy(cells = ShortList(cells :+ cell))

  def insertCell(cell: NotebookCell, after: Option[String]): Notebook = {
    val insertIndex = after.fold(0)(id => cells.indexWhere(_.id == id)) match {
      case -1 => 0
      case n => n
    }

    copy(
      cells = ShortList(
        cells.take(insertIndex + 1) ++ (cell :: cells.drop(insertIndex + 1))))
  }

  def deleteCell(id: String): Notebook = copy(cells = ShortList(cells.collect {
    case cell if cell.id != id => cell
  }))

  def setResults(id: String, results: List[Result]): Notebook = updateCell(TinyString(id)) {
    cell => cell.copy(results = ShortList(results))
  }
}
object Notebook extends MessageCompanion[Notebook](2)

final case class RunCell(notebook: ShortString, id: ShortList[TinyString]) extends Message
object RunCell extends MessageCompanion[RunCell](3)

final case class CellResult(notebook: ShortString, id: TinyString, result: Result) extends Message
object CellResult extends MessageCompanion[CellResult](4)


sealed trait ContentEdit {
  def pos: Int
  def lengthDelta: Int

  def rebase(other: ContentEdits): ContentEdits = ContentEdits(ShortList(ContentEdit.rebaseAll(this, other.edits)._1))

  def applyTo(rope: Rope): Rope = this match {
    case Insert(pos, content) => rope.insertAt(pos, Rope(content))
    case Delete(pos, length)  => rope.delete(pos, length)
  }

  def nonEmpty: Boolean
}

final case class Insert(pos: Int, content: String) extends ContentEdit {
  def lengthDelta: Int = content.length

  def nonEmpty: Boolean = content.nonEmpty

  override def toString: String = s"""Insert($pos, "$content")"""
}

object Insert {
  implicit val insertTag: Discriminator[ContentEdit, Insert, Byte] = Discriminator(0)
}

final case class Delete(pos: Int, length: Int) extends ContentEdit {
  override def lengthDelta: Int = -length

  override def nonEmpty: Boolean = length != 0
}

object Delete {
  implicit val deleteTag: Discriminator[ContentEdit, Delete, Byte] = Discriminator(1)
}

object ContentEdit {
  implicit val discriminated: Discriminated[ContentEdit, Byte] = Discriminated(byte)

  // rebase a onto b and b onto a
  def rebase(a: ContentEdit, b: ContentEdit): (List[ContentEdit], List[ContentEdit]) = (a, b) match {

    // if A is before A, or A and B are at the same spot but (A is shorter than B or equal in length but lexically before B)
    // then A comes before B.
    case (winner @ Insert(pos1, content1), Insert(pos2, content2)) if
      (pos1 < pos2) ||
        (pos1 == pos2 &&
          (content1.length < content2.length || (content1.length == content2.length && content1 < content2))) =>
            List(winner) -> List(Insert(pos2 + content1.length, content2))

    // Otherwise insert B comes before insert A
    case (Insert(pos1, content1), winner @ Insert(_, content2)) =>
      List(Insert(pos1 + content2.length, content1)) -> List(winner)

    // If an insert comes before a delete, the delete moves forward by its length
    case (winner @ Insert(iPos, content), Delete(dPos, dLength)) if iPos <= dPos =>
      List(winner) -> List(Delete(dPos + content.length, dLength))

    case (Delete(dPos, dLength), winner @ Insert(iPos, content)) if iPos <= dPos =>
      List(Delete(dPos + content.length, dLength)) -> List(winner)

    // insert is in the middle of delete; delete has to split into before-insert and after-insert parts
    // and insert has to move back to where the delete started
    case (Insert(iPos, content), Delete(dPos, dLength)) if iPos < dPos + dLength =>
      val beforeLength = iPos - dPos
      List(Insert(dPos, content)) -> List(Delete(dPos, beforeLength), Delete(dPos + content.length, dLength - beforeLength))

    case (Delete(dPos, dLength), Insert(iPos, content)) if iPos < dPos + dLength =>
      val beforeLength = iPos - dPos
      List(Delete(dPos, beforeLength), Delete(dPos + content.length, dLength - beforeLength)) -> List(Insert(dPos, content))

    // insert is after delete - insert has to move back by deletion length
    case (Insert(iPos, content), del @ Delete(dPos, dLength)) =>
      List(Insert(iPos - dLength, content)) -> List(del)

    case (del @ Delete(dPos, dLength), Insert(iPos, content)) =>
      List(del) -> List(Insert(iPos - dLength, content))

    // delete A comes entirely before delete B;  move B back by A's length
    case (winner @ Delete(pos1, length1), Delete(pos2, length2)) if pos1 + length1 <= pos2 =>
      List(winner) -> List(Delete(pos2 - length1, length2))

    // delete B comes entirely before delete A; move A back by B's length
    case (Delete(pos1, length1), winner @ Delete(pos2, length2)) if pos2 + length2 <= pos1 =>
      List(Delete(pos1 - length2, length1)) -> List(winner)

    // B is entirely within A; A should shorten by B's length and B should disappear
    case (Delete(pos1, length1), Delete(pos2, length2)) if pos2 >= pos1 && pos2 + length2 <= pos1 + length1 =>
      List(Delete(pos1, length1 - length2)) -> Nil

    // A is entirely within B; opposite of above
    case (Delete(pos1, length1), Delete(pos2, length2)) if pos1 >= pos2 && pos1 + length1 <= pos2 + length2 =>
      Nil -> List(Delete(pos2, length2 - length1))

    // delete B starts in the middle of delete A; A should stop where B starts, and B should move to where A starts and shorten itself by the overlap
    case (Delete(pos1, length1), Delete(pos2, length2)) if pos2 > pos1 =>
      val overlap = pos1 + length1 - pos2
      List(Delete(pos1, length1 - overlap)) -> List(Delete(pos1, length2 - overlap))

    // delete A starts in the middle of B; opposite of above
    case (Delete(pos1, length1), Delete(pos2, length2)) if pos1 > pos2 =>
      val overlap = pos2 + length2 - pos1
      List(Delete(pos2, length1 - overlap)) -> List(Delete(pos2, length2 - overlap))
  }

  // rebase edit onto all of edits, and all of edits onto edit
  def rebaseAll(edit: ContentEdit, edits: Seq[ContentEdit]): (List[ContentEdit], List[ContentEdit]) = {
    val rebasedOther = ListBuffer[ContentEdit]()
    val rebasedEdit = edits.foldLeft(List(edit)) {
      (as, b) =>
        var bs = List(b)
        val rebasedAs = as.flatMap {
          a =>
            bs match {
              case Nil => List(a)
              case one :: Nil =>
                val rebased = rebase(a, one)
                bs = rebased._2
                rebased._1
              case more =>
                val rebased = rebaseAll(a, more)
                bs = rebased._1
                rebased._1
            }
        }
        rebasedOther ++= bs
        rebasedAs
    }
    rebasedEdit -> rebasedOther.toList
  }
}

/**
  * Represents a sequence of [[ContentEdit]]s. Each edit in the sequence must be based upon (or independent of) the
  * edits before it.
  */
final case class ContentEdits(edits: ShortList[ContentEdit]) extends AnyVal {
  def applyTo(rope: Rope): Rope = rope.withEdits(this)

  /**
    * Given another set of edits which would act upon the same content as this set of edits, produce a new set of edits
    * which would have the same effect as this set of edits but are based upon the given edits.
    */
  def rebase(other: ContentEdits): ContentEdits = {
    // each edit in this set must be rebased to all the edits in the other set.
    // but we also have to track how the other edits are affected by each subsequent edit in this set, so that
    // the edits after it know how to rebase.
    val result = new ListBuffer[ContentEdit]
    val iter = edits.iterator.filter(_.nonEmpty)
    var otherEdits: List[ContentEdit] = other.edits

    while (iter.hasNext) {
      val edit = iter.next()
      val (rebasedEdit, rebasedOther) = ContentEdit.rebaseAll(edit, otherEdits)
      result ++= rebasedEdit
      otherEdits = rebasedOther
    }
    ContentEdits(ShortList(result.toList))
//    ContentEdits(ShortList(edits.flatMap(_.rebase(other).edits)))
  }

  def rebase(other: ContentEdit): ContentEdits = rebase(ContentEdits(other))

  def size: Int = edits.size
}

object ContentEdits {
  def apply(edits: ContentEdit*): ContentEdits = ContentEdits(ShortList(edits.toList))
}

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
      i.copy(after = Some(cell2.id))

    case (u@UpdateCell(_, _, _, id1, edits1), UpdateCell(_, _, _, id2, edits2)) if id1 == id2 =>
      // we both tried to edit the same cell. Transform first edits so they apply to the document state as it exists after the second edits are already applied.

      u.copy(edits = edits1.rebase(edits2))

    // all other cases should be independent (TODO: they're not yet, though)
    case _ => this

  }
}

object NotebookUpdate {
  def unapply(message: Message): Option[NotebookUpdate] = message match {
    case msg: NotebookUpdate => Some(msg)
    case _ => None
  }
}

final case class UpdateCell(notebook: ShortString, globalVersion: Int, localVersion: Int, id: TinyString, edits: ContentEdits) extends Message with NotebookUpdate
object UpdateCell extends MessageCompanion[UpdateCell](5)

final case class InsertCell(notebook: ShortString, globalVersion: Int, localVersion: Int, cell: NotebookCell, after: Option[TinyString]) extends Message with NotebookUpdate
object InsertCell extends MessageCompanion[InsertCell](6)

final case class CompletionsAt(notebook: ShortString, id: TinyString, pos: Int, completions: ShortList[Completion]) extends Message
object CompletionsAt extends MessageCompanion[CompletionsAt](7)

final case class ParametersAt(notebook: ShortString, id: TinyString, pos: Int, signatures: Option[Signatures]) extends Message
object ParametersAt extends MessageCompanion[ParametersAt](8)

final case class KernelStatus(notebook: ShortString, update: KernelStatusUpdate) extends Message
object KernelStatus extends MessageCompanion[KernelStatus](9)

final case class UpdateConfig(notebook: ShortString, globalVersion: Int, localVersion: Int, config: NotebookConfig) extends Message with NotebookUpdate
object UpdateConfig extends MessageCompanion[UpdateConfig](10)

final case class SetCellLanguage(notebook: ShortString, globalVersion: Int, localVersion: Int, id: TinyString, language: TinyString) extends Message with NotebookUpdate
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

final case class DeleteCell(notebook: ShortString, globalVersion: Int, localVersion: Int, id: TinyString) extends Message with NotebookUpdate
object DeleteCell extends MessageCompanion[DeleteCell](15)