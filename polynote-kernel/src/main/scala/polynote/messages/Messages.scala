package polynote.messages

import cats.syntax.either._
import io.circe.{Decoder, Encoder}
import polynote.kernel._
import scodec.Codec
import scodec.bits.{BitVector, ByteVector}
import scodec.codecs._
import scodec.codecs.implicits._
import io.circe.generic.semiauto._
import polynote.config.{DependencyConfigs, PolynoteConfig, RepositoryConfig, SparkConfig, SparkPropertySet}
import polynote.data.Rope
import polynote.runtime.{CellRange, StreamingDataRepr, TableOp}
import shapeless.cachedImplicit
import zio.{IO, ZIO}

sealed trait Message

object Message {
  implicit val discriminated: Discriminated[Message, Byte] = Discriminated(byte)

  val codec: Codec[Message] = Codec[Message]

  def decode(bytes: ByteVector): IO[CodecError, Message] = ZIO.fromEither {
    codec.decode(bytes.toBitVector).toEither
      .map(_.value)
      .leftMap {
        err => CodecError(err)
      }
  }

  def encode(msg: Message): IO[CodecError, BitVector] = ZIO.fromEither {
    codec.encode(msg).toEither.leftMap {
      err => CodecError(err)
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
  splitDisplay: Boolean = false,
  wrapOutput: Boolean = false,
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
  metadata: CellMetadata = CellMetadata(),
  comments: ShortMap[CommentID, Comment] = Map.empty[CommentID, Comment]
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
  sparkConfig: Option[ShortMap[String, String]],
  sparkTemplate: Option[SparkPropertySet],
  env: Option[ShortMap[String, String]],
  scalaVersion: Option[String] = None,
  jvmArgs: Option[TinyList[String]] = None
)

object NotebookConfig {
  implicit val encoder: Encoder[NotebookConfig] = deriveEncoder[NotebookConfig]
  implicit val decoder: Decoder[NotebookConfig] = deriveDecoder[NotebookConfig]

  def empty = NotebookConfig(None, None, None, None, None, None)

  def fromPolynoteConfig(config: PolynoteConfig): NotebookConfig = {
    val smallDependencies: DependencyConfigs = ShortMap(config.dependencies.map {
      case (lang, deps) =>
        TinyString(lang) -> ShortList(deps.map(TinyString(_)))
    })
    NotebookConfig(
      dependencies = Option(smallDependencies),
      exclusions = Option(config.exclusions),
      repositories = Option(config.repositories),
      sparkConfig = config.spark.map(SparkConfig.toMap),
      sparkTemplate = for {
        spark       <- config.spark
        defaultName <- spark.defaultPropertySet
        propSets    <- spark.propertySets
        default     <- propSets.find(_.name == defaultName)
      } yield default,
      env = Option(config.env),
      scalaVersion = config.kernel.scalaVersion,
      jvmArgs = config.kernel.jvmArgs.map(_.toList)
    )
  }

  trait Provider {
    val notebookConfig: NotebookConfig
  }
}

final case class Notebook(path: ShortString, cells: ShortList[NotebookCell], config: Option[NotebookConfig]) extends Message {
  def map(fn: NotebookCell => NotebookCell): Notebook = copy(
    cells = ShortList(cells.map(fn))
  )

  def updateCell(id: CellID)(fn: NotebookCell => NotebookCell): Notebook = map {
    case cell if cell.id == id => fn(cell)
    case cell => cell
  }

  def editCell(id: CellID, edits: ContentEdits, metadata: Option[CellMetadata]): Notebook = updateCell(id) {
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

  def moveCell(id: CellID, after: CellID): Notebook = {
    val srcIndex = cells.indexWhere(_.id == id)
    if (srcIndex >= 0) {
      val cell = cells(srcIndex)
      val removed = cells.patch(srcIndex, Nil, 1)
      val targetIndex = if (after < 0) 0 else removed.indexWhere(_.id == after) + 1
      if (targetIndex >= 0) {
        copy(cells = removed.patch(targetIndex, List(cell), 0))
      } else this
    } else this
  }

  def createComment(id: CellID, comment: Comment): Notebook = updateCell(id) {
    cell =>
      require(!cell.comments.contains(comment.uuid), s"Comment with id ${comment.uuid} already exists!")
      cell.copy(comments = cell.comments.updated(comment.uuid, comment))
  }

  // TODO: We need to update all child comments if the range has been changed and this is a root comment!
  def updateComment(id: CellID, commentId: CommentID, range: CellRange, content: String): Notebook = updateCell(id) {
    cell =>
      require(cell.comments.contains(commentId), s"Comment with id $commentId does not exist!")
      val comment = cell.comments(commentId).copy(content = content, range = range)
      cell.copy(comments = cell.comments.updated(commentId, comment))
  }

  private def rootForRange(cell: NotebookCell, range: CellRange): Comment = {
      cell.comments.values.reduce[Comment] {
        case (acc, next) =>
          if (next.range == acc.range && next.createdAt < acc.createdAt) next else acc
      }
  }

  def deleteComment(id: CellID, commentId: CommentID): Notebook = updateCell(id) {
    cell =>
      require(cell.comments.contains(commentId), s"Comment with id $commentId does not exist!")
      val deletedCell = cell.comments(commentId)
      val withoutDeleted = cell.comments - commentId

      val rootComment = rootForRange(cell, deletedCell.range)

      if (deletedCell == rootComment) {
        // this is a root comment, we need to delete all the comments that point to it
        val remaining = withoutDeleted.filter {
          case (_, Comment(_, range, _, _, _, _)) if range == deletedCell.range => false
          case _ => true
        }
        cell.copy(comments = remaining)
      } else {
        cell.copy(comments = withoutDeleted)
      }
  }

  /**
    * @return A copy of this notebook without any results
    */
  def withoutResults: Notebook = copy(cells = ShortList(cells.map(_.copy(results = ShortList.Nil))))

  /**
    * @return All of the results in this notebook, in order, as [[CellResult]]s.
    */
  def results: List[CellResult] = cells.flatMap(cell => cell.results.map(CellResult(cell.id, _)))

  def setResults(id: CellID, results: List[Result]): Notebook = updateCell(id) {
    cell => cell.copy(results = ShortList(results))
  }

  def setMetadata(id: CellID, metadata: CellMetadata): Notebook = updateCell(id) {
    cell => cell.copy(metadata = metadata)
  }

  def getCell(id: CellID): Option[NotebookCell] = cells.find(_.id == id)

  def cell(id: CellID): NotebookCell = getCell(id).getOrElse(throw new NoSuchElementException(s"Cell $id does not exist"))
}

object Notebook extends MessageCompanion[Notebook](2) {
  implicit val codec: Codec[Notebook] = cachedImplicit
}

final case class RunCell(ids: ShortList[CellID]) extends Message
object RunCell extends MessageCompanion[RunCell](3)

sealed trait NotebookUpdate extends Message {
  def globalVersion: Int
  def localVersion: Int

  def withVersions(global: Int, local: Int): NotebookUpdate = this match {
    case u @ UpdateCell(_, _, _, _, _)         => u.copy(globalVersion = global, localVersion = local)
    case i @ InsertCell(_, _, _, _)            => i.copy(globalVersion = global, localVersion = local)
    case d @ DeleteCell(_, _, _)               => d.copy(globalVersion = global, localVersion = local)
    case u @ UpdateConfig(_, _, _)             => u.copy(globalVersion = global, localVersion = local)
    case l @ SetCellLanguage(_, _, _, _)       => l.copy(globalVersion = global, localVersion = local)
    case o @ SetCellOutput(_, _, _, _)         => o.copy(globalVersion = global, localVersion = local)
    case cc @ CreateComment(_, _, _, _)        => cc.copy(globalVersion = global, localVersion = local)
    case dc @ DeleteComment(_, _, _, _)        => dc.copy(globalVersion = global, localVersion = local)
    case uc @ UpdateComment(_, _, _, _, _, _)  => uc.copy(globalVersion = global, localVersion = local)
    case mc @ MoveCell(_, _, _, _)             => mc.copy(globalVersion = global, localVersion = local)
  }

  // transform this update so that it has the same effect when applied after the given update
  def rebase(prev: NotebookUpdate, client: Boolean = false): NotebookUpdate = (this, prev) match {
    case (i@InsertCell(_, _, cell1, after1), InsertCell(_, _, cell2, after2)) if after1 == after2 =>
      // we both tried to insert a cell after the same cell. Transform the first update so it inserts after the cell created by the second update.
      i.copy(after = cell2.id)

    case (u@UpdateCell(_, _, id1, edits1, _), UpdateCell(_, _, id2, edits2, _)) if id1 == id2 =>
      // we both tried to edit the same cell. Transform first edits so they apply to the document state as it exists after the second edits are already applied.

      u.copy(edits = edits1.rebase(edits2, client))

    // all other cases should be independent (TODO: they're not yet, though)
    case _ => this

  }

  def rebaseAll(prev: List[NotebookUpdate], log: Option[StringBuilder] = None): (NotebookUpdate, List[NotebookUpdate]) = this match {
    case UpdateCell(_, _, _, ContentEdits(ShortList.Nil), _) => (this, Nil)
    case self@UpdateCell(_, _, id, myEdits, _) =>
      val conflicting = prev.collect {
        case update@UpdateCell(_, _, `id`, _, _) => update
      }

      val (result, updatedPrev) = conflicting.foldLeft((myEdits, List.empty[NotebookUpdate])) {
        case ((myEdits, newUpdates), nextUpdate) =>
          val (myRebased, theirRebased) = myEdits.rebaseBoth(nextUpdate.edits)
          log.foreach(log => log ++= s"  $nextUpdate => $myRebased")
          (myRebased, nextUpdate.copy(edits = ContentEdits(theirRebased)) :: newUpdates)
      }

      self.copy(edits = result) -> updatedPrev.reverse

    case _ => (prev.foldLeft(this)(_ rebase _), Nil)
  }

  def applyTo(notebook: Notebook): Notebook = this match {
    case InsertCell(_, _, cell, after) => notebook.insertCell(cell, after)
    case DeleteCell(_, _, id)          => notebook.deleteCell(id)
    case UpdateCell(_, _, id, edits, metadata) =>
      metadata.foldLeft(notebook.editCell(id, edits, metadata)) {
        (nb, meta) => nb.setMetadata(id, meta)
      }
    case UpdateConfig(_, _, config)    => notebook.copy(config = Some(config))
    case SetCellLanguage(_, _, id, lang) => notebook.updateCell(id)(_.copy(language = lang))
    case SetCellOutput(_, _, id, output) => notebook.setResults(id, output.toList)
    case CreateComment(_, _, cellId, comment) => notebook.createComment(cellId, comment)
    case UpdateComment(_, _, cellId, commentId, range, content) => notebook.updateComment(cellId, commentId, range, content)
    case DeleteComment(_, _, cellId, commentId) => notebook.deleteComment(cellId, commentId)
    case MoveCell(_, _, cellId, after) => notebook.moveCell(cellId, after)
  }

  /**
    * By default, received NotebookUpdates are echoed to other subscribers but not to the originating one.
    * This overrides that, echoing this NotebookUpdate to the originating subscriber as well.
    */
  def echoOriginatingSubscriber: Boolean = false

}

object NotebookUpdate {
  def unapply(message: Message): Option[NotebookUpdate] = message match {
    case msg: NotebookUpdate => Some(msg)
    case _ => None
  }

  implicit val discriminated: Discriminated[NotebookUpdate, Byte] = Discriminated(byte)
  val codec: Codec[NotebookUpdate] = Codec[NotebookUpdate]
}

abstract class NotebookUpdateCompanion[T <: NotebookUpdate](msgTypeId: Byte) extends MessageCompanion[T](msgTypeId) {
  implicit final val updateDiscriminator: Discriminator[NotebookUpdate, T, Byte] = Discriminator(msgTypeId)
}

final case class CellResult(id: CellID, result: Result) extends Message
object CellResult extends MessageCompanion[CellResult](4)

final case class UpdateCell(globalVersion: Int, localVersion: Int, id: CellID, edits: ContentEdits, metadata: Option[CellMetadata]) extends Message with NotebookUpdate
object UpdateCell extends NotebookUpdateCompanion[UpdateCell](5)

final case class InsertCell(globalVersion: Int, localVersion: Int, cell: NotebookCell, after: CellID) extends Message with NotebookUpdate {
  override def echoOriginatingSubscriber: Boolean = true
}
object InsertCell extends NotebookUpdateCompanion[InsertCell](6)

final case class Comment(
  uuid: CommentID,
  range: CellRange, // note, cells are sorted by creation time
  author: TinyString,
  authorAvatarUrl: Option[String],
  createdAt: Long,
  content: ShortString
)

object Comment {
  implicit val encoder: Encoder.AsObject[Comment] = deriveEncoder
  implicit val decoder: Decoder[Comment] = deriveDecoder
}

final case class CreateComment(globalVersion: Int, localVersion: Int, cellId: CellID, comment: Comment) extends Message with NotebookUpdate
object CreateComment extends NotebookUpdateCompanion[CreateComment](29)

final case class UpdateComment(globalVersion: Int, localVersion: Int, cellId: CellID, commentId: CommentID, range: CellRange, content: ShortString) extends Message with NotebookUpdate
object UpdateComment extends NotebookUpdateCompanion[UpdateComment](30)

final case class DeleteComment(globalVersion: Int, localVersion: Int, cellId: CellID, commentId: CommentID) extends Message with NotebookUpdate
object DeleteComment extends NotebookUpdateCompanion[DeleteComment](31)

final case class CompletionsAt(id: CellID, pos: Int, completions: ShortList[Completion]) extends Message
object CompletionsAt extends MessageCompanion[CompletionsAt](7)

final case class ParametersAt(id: CellID, pos: Int, signatures: Option[Signatures]) extends Message
object ParametersAt extends MessageCompanion[ParametersAt](8)

final case class KernelStatus(update: KernelStatusUpdate) extends Message
object KernelStatus extends MessageCompanion[KernelStatus](9)

final case class UpdateConfig(globalVersion: Int, localVersion: Int, config: NotebookConfig) extends Message with NotebookUpdate
object UpdateConfig extends NotebookUpdateCompanion[UpdateConfig](10)

final case class SetCellLanguage(globalVersion: Int, localVersion: Int, id: CellID, language: TinyString) extends Message with NotebookUpdate {
  override def echoOriginatingSubscriber: Boolean = true
}
object SetCellLanguage extends NotebookUpdateCompanion[SetCellLanguage](11)

final case class MoveCell(globalVersion: Int, localVersion: Int, id: CellID, after: CellID) extends Message with NotebookUpdate
object MoveCell extends NotebookUpdateCompanion[MoveCell](33)

final case class NotebookSaved(path: ShortString, timestamp: Long) extends Message
object NotebookSaved extends MessageCompanion[NotebookSaved](35)

final case class StartKernel(level: Byte) extends Message
object StartKernel extends MessageCompanion[StartKernel](12) {
  // TODO: should probably make this an enum that codecs to a byte, but don't want to futz with that right now
  final val NoRestart = 0.toByte
  final val WarmRestart = 1.toByte
  final val ColdRestart = 2.toByte
  final val Kill = 3.toByte
}

final case class fsNotebook(path: ShortString, lastSaved: Long)
final case class ListNotebooks(paths: List[fsNotebook]) extends Message
object ListNotebooks extends MessageCompanion[ListNotebooks](13)

final case class CreateNotebook(path: ShortString, maybeContent: Option[String] = None, maybeTemplatePath: Option[String] = None) extends Message
object CreateNotebook extends MessageCompanion[CreateNotebook](14)

final case class RenameNotebook(path: ShortString, newPath: ShortString) extends Message
object RenameNotebook extends MessageCompanion[RenameNotebook](25)

final case class CopyNotebook(path: ShortString, newPath: ShortString) extends Message
object CopyNotebook extends MessageCompanion[CopyNotebook](27)

final case class DeleteNotebook(path: ShortString) extends Message
object DeleteNotebook extends MessageCompanion[DeleteNotebook](26)

final case class DeleteCell(globalVersion: Int, localVersion: Int, id: CellID) extends Message with NotebookUpdate {
  override def echoOriginatingSubscriber: Boolean = true
}
object DeleteCell extends NotebookUpdateCompanion[DeleteCell](15)

final case class SetCellOutput(globalVersion: Int, localVersion: Int, id: CellID, output: Option[Output]) extends Message with NotebookUpdate
object SetCellOutput extends NotebookUpdateCompanion[SetCellOutput](22)

final case class Identity(name: TinyString, avatar: Option[ShortString])

final case class ServerHandshake(
  interpreters: TinyMap[TinyString, TinyString],
  serverVersion: TinyString,
  serverCommit: TinyString,
  identity: Option[Identity],
  sparkTemplates: List[SparkPropertySet],
  notebookTemplates: List[ShortString],
  notifications: Boolean
) extends Message
object ServerHandshake extends MessageCompanion[ServerHandshake](16)

final case class CancelTasks(path: ShortString, taskId: Option[TinyString]) extends Message
object CancelTasks extends MessageCompanion[CancelTasks](18)

final case class ClearOutput() extends Message
object ClearOutput extends MessageCompanion[ClearOutput](21)

final case class NotebookVersion(notebook: ShortString, globalVersion: Int) extends Message
object NotebookVersion extends MessageCompanion[NotebookVersion](23)

final case class RunningKernels(statuses: TinyList[(ShortString, KernelBusyState)]) extends Message
object RunningKernels extends MessageCompanion[RunningKernels](24)

final case class KeepAlive(payload: Byte) extends Message
object KeepAlive extends MessageCompanion[KeepAlive](32)

final case class NotebookSearchResult(
  path: ShortString,
  cellID: CellID,
  cellContent: ShortString
)
final case class SearchNotebooks(query: ShortString, notebookSearchResults: List[NotebookSearchResult]) extends Message
object SearchNotebooks extends MessageCompanion[SearchNotebooks](34)

final case class GoToDefinitionRequest(source: Either[String, CellID], pos: Int, reqId: Int) extends Message
object GoToDefinitionRequest extends MessageCompanion[GoToDefinitionRequest](36)

/**
  *
  * @param uri     A URI that points either to a cell in the same notebook (a bare fragment identifier), or a relative URL
  *                  to retrieve the contents of a dependency source file.
  * @param line    The line within the pointed source at which the definition starts
  * @param column  The column within the pointed source at which the definition starts
  */
final case class DefinitionLocation(uri: String, line: Int, column: Int)

final case class GoToDefinitionResponse(reqId: Int, location: TinyList[DefinitionLocation]) extends Message
object GoToDefinitionResponse extends MessageCompanion[GoToDefinitionResponse](37)


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
  handleType: HandleType,
  handle: Int,
  count: Int,
  data: Either[Error, Array[ByteVector32]]
) extends Message

object HandleData extends MessageCompanion[HandleData](17)

/********************************************************
 ** Specifically for streams of structs (i.e. tables)  **
 *******************************************************/

final case class ModifyStream(fromHandle: Int, ops: TinyList[TableOp], newRepr: Option[StreamingDataRepr]) extends Message
object ModifyStream extends MessageCompanion[ModifyStream](19) {
  import TableOpCodec.tableOpCodec
  import ValueReprCodec.streamingDataReprCodec
  implicit val codec: Codec[ModifyStream] = shapeless.cachedImplicit
}

final case class ReleaseHandle(handleType: HandleType, handle: Int) extends Message
object ReleaseHandle extends MessageCompanion[ReleaseHandle](20)

final case class CurrentSelection(cellID: CellID, range: CellRange) extends Message
object CurrentSelection extends MessageCompanion[CurrentSelection](28)