package polynote.kernel

import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder}
import polynote.messages.{CellID, ShortString, TinyList, TinyMap, TinyString}
import polynote.runtime.CellRange
import scodec.codecs.{Discriminated, Discriminator, byte}
import scodec.{Attempt, Codec, Err}
import scodec.codecs.implicits._
import shapeless.cachedImplicit
import zio.Cause

import scala.reflect.internal.util.Position
import scala.util.Try

// TODO: should just make a codec for Position? To avoid extra object?
// TODO: can't recover line number from just this info. Should we store that?
final case class Pos(sourceId: String, start: Int, end: Int, point: Int) {
  def this(position: Position) = this(
    position.source.toString,
    if (position.isDefined) Try(position.start).getOrElse(-1) else -1,
    if (position.isDefined) Try(position.end).getOrElse(-1) else -1,
    position.pointOrElse(-1)
  )
}

object Pos {
  implicit val encoder: Encoder[Pos] = deriveEncoder[Pos]
  implicit val decoder: Decoder[Pos] = deriveDecoder[Pos]
}

final case class KernelReport(pos: Pos, msg: String, severity: Int) {
  def severityString: String = severity match {
    case KernelReport.Info => "Info"
    case KernelReport.Warning => "Warning"
    case _ => "Error"
  }

  override def toString: String =
    s"""$severityString: $msg (${pos.start})""" // TODO: line and column instead
}

object KernelReport {
  final val Info = 0
  final val Warning = 1
  final val Error = 2
  implicit val encoder: Encoder[KernelReport] = deriveEncoder[KernelReport]
  implicit val decoder: Decoder[KernelReport] = deriveDecoder[KernelReport]
}

sealed trait CompletionType

object CompletionType {
  final case object Term extends CompletionType
  final case object Field extends CompletionType
  final case object Method extends CompletionType
  final case object Package extends CompletionType
  final case object TraitType extends CompletionType
  final case object ClassType extends CompletionType
  final case object Module extends CompletionType
  final case object TypeAlias extends CompletionType
  final case object Keyword extends CompletionType
  final case object Unknown extends CompletionType

  // these were chosen to line up with LSP, for no reason other than convenience with Monaco
  val fromByte: PartialFunction[Byte, CompletionType] = {
    case 0  => Unknown
    case 5  => Term
    case 4  => Field
    case 1  => Method
    case 18 => Package
    case 7  => TraitType
    case 6  => ClassType
    case 8  => Module
    case 17 => TypeAlias
    case 13 => Keyword
  }

  def toByte(typ: CompletionType): Byte = typ match {
    case Unknown   => 0
    case Term      => 5
    case Field     => 4
    case Method    => 1
    case Package   => 18
    case TraitType => 7
    case ClassType => 6
    case Module    => 8
    case TypeAlias => 17
    case Keyword   => 13
  }

  implicit val codec: Codec[CompletionType] = scodec.codecs.byte.exmap(
    fromByte.lift andThen (opt => Attempt.fromOption[CompletionType](opt, Err("Invalid completion type number"))),
    toByte _ andThen Attempt.successful
  )
}

final case class Completion(
  name: TinyString,
  typeParams: TinyList[TinyString],
  paramLists: TinyList[TinyList[(TinyString, ShortString)]],
  resultType: ShortString,
  completionType: CompletionType,
  insertText: Option[ShortString] = None)

final case class ParameterHint(
  name: TinyString,
  typeName: TinyString,
  docString: Option[ShortString]) {
  override def toString: String = if (typeName.nonEmpty) s"$name: $typeName" else name
}

final case class ParameterHints(
  name: TinyString,
  docString: Option[ShortString],
  parameters: TinyList[ParameterHint])

final case class Signatures(
  hints: TinyList[ParameterHints],
  activeSignature: Byte,
  activeParameter: Byte)

sealed trait KernelStatusUpdate {
  def isRelevant(subscriber: Int): Boolean
  def forSubscriber(subscriber: Int): KernelStatusUpdate = this
}

sealed trait AlwaysRelevant { self: KernelStatusUpdate =>
  override def isRelevant(subscriber: Int): Boolean = true
}

object KernelStatusUpdate {
  implicit val discriminated: Discriminated[KernelStatusUpdate, Byte] = Discriminated(byte)
  implicit val codec: Codec[KernelStatusUpdate] = cachedImplicit
}

abstract class KernelStatusUpdateCompanion[T <: KernelStatusUpdate](id: Byte) {
  implicit val discriminator: Discriminator[KernelStatusUpdate, T, Byte] = Discriminator(id)
}

final case class SymbolInfo(
  name: TinyString,
  typeName: TinyString,
  valueText: TinyString,
  availableViews: TinyList[TinyString])

final case class UpdatedSymbols(
  newOrUpdated: TinyList[SymbolInfo],
  removed: TinyList[TinyString]
) extends KernelStatusUpdate with AlwaysRelevant

object UpdatedSymbols extends KernelStatusUpdateCompanion[UpdatedSymbols](0)

sealed trait TaskStatus {
  def isDone: Boolean
}

sealed trait DoneStatus extends TaskStatus { final val isDone: Boolean = true }
sealed trait NotDoneStatus extends TaskStatus { final val isDone: Boolean = false }

case object Complete extends DoneStatus
case object Queued extends NotDoneStatus
case object Running extends NotDoneStatus
case object ErrorStatus extends DoneStatus

object TaskStatus {


  val fromByte: PartialFunction[Byte, TaskStatus] = {
    case 0 => Complete
    case 1 => Running
    case 2 => Queued
    case 3 => ErrorStatus
  }

  def toByte(taskStatus: TaskStatus): Byte = taskStatus match {
    case Complete => 0
    case Running => 1
    case Queued => 2
    case ErrorStatus => 3
  }

  implicit val codec: Codec[TaskStatus] = byte.exmap(
    fromByte.lift andThen (Attempt.fromOption(_, Err("Invalid task status byte"))),
    s => Attempt.successful(toByte(s))
  )

  implicit val ordering: Ordering[TaskStatus] = new Ordering[TaskStatus] {
    // this isn't the most concise comparison routine, but it should give compiler warnings if any statuses are added w/o being handled
    def compare(x: TaskStatus, y: TaskStatus): Int = (x, y) match {
      case (Complete, Complete) | (Running, Running) | (Queued, Queued) | (ErrorStatus, ErrorStatus) => 0
      case (Complete, _) => 1
      case (_, Complete) => -1
      case (Queued, _)   => -1
      case (_, Queued)   => 1
      case (ErrorStatus, _)    => 1
      case (_, ErrorStatus)    => -1
    }
  }
}

final case class TaskInfo(
  id: TinyString,
  label: TinyString,
  detail: ShortString,
  status: TaskStatus,
  progress: Byte = 0,
  parent: Option[TinyString] = None) {

  def running: TaskInfo = copy(status = Running)
  def completed: TaskInfo = copy(status = Complete, progress = 255.toByte)
  def failed: TaskInfo = if (status == Complete) this else copy(status = ErrorStatus, progress = 255.toByte)
  def failed(err: Cause[Throwable]): TaskInfo = if (status == Complete) this else {
    val errMsg = Option(err.squash.getMessage).getOrElse(err.squash.toString)
    copy(status = ErrorStatus, detail = ShortString.truncate(errMsg), progress = 255.toByte)
  }
  def done(status: DoneStatus): TaskInfo = if (this.status.isDone) this else copy(status = status, progress = 255.toByte)
  def progress(fraction: Double): TaskInfo = copy(progress = (fraction * 255).toByte)
  def progress(fraction: Double, detailOpt: Option[String]): TaskInfo = copy(progress = (fraction * 255).toByte, detail = detailOpt.getOrElse(detail))
  def progressFraction: Double = progress.toDouble / 255
}

object TaskInfo {
  def apply(id: String): TaskInfo = TaskInfo(id, "", "", Queued)
}

final case class UpdatedTasks(
  tasks: TinyList[TaskInfo]
) extends KernelStatusUpdate with AlwaysRelevant

object UpdatedTasks extends KernelStatusUpdateCompanion[UpdatedTasks](1) {
  def one(info: TaskInfo): UpdatedTasks = UpdatedTasks(List(info))
}

final case class KernelBusyState(busy: Boolean, alive: Boolean) extends KernelStatusUpdate with AlwaysRelevant {
  def setBusy: KernelBusyState = copy(busy = true)
  def setIdle: KernelBusyState = copy(busy = false)
  def setAlive: KernelBusyState = copy(alive = true)
  def setDead: KernelBusyState = copy(alive = false)
}
object KernelBusyState extends KernelStatusUpdateCompanion[KernelBusyState](2)

//                                           key          html
final case class KernelInfo(content: TinyMap[ShortString, String]) extends KernelStatusUpdate with AlwaysRelevant {
  def combine(other: KernelInfo): KernelInfo = {
    copy(TinyMap(content ++ other.content))
  }

  def +(kv: (String, String)): KernelInfo = copy(content = TinyMap(content + (ShortString(kv._1) -> kv._2)))
}
object KernelInfo extends KernelStatusUpdateCompanion[KernelInfo](3) {
  def apply(tups: (String, String)*): KernelInfo = KernelInfo(TinyMap(tups.map {
    case (k, v) => ShortString(k) -> v
  }.toMap))
}

final case class ExecutionStatus(cellID: CellID, pos: Option[CellRange]) extends KernelStatusUpdate with AlwaysRelevant
object ExecutionStatus extends KernelStatusUpdateCompanion[ExecutionStatus](4)

final case class Presence(id: Int, name: TinyString, avatar: Option[ShortString])
final case class PresenceUpdate(added: TinyList[Presence], removed: TinyList[Int]) extends KernelStatusUpdate with AlwaysRelevant {
  override def forSubscriber(subscriber: Int): KernelStatusUpdate =
    copy(added = added.filterNot(_.id == subscriber), removed = removed.filterNot(_ == subscriber))
}
object PresenceUpdate extends KernelStatusUpdateCompanion[PresenceUpdate](5)

final case class PresenceSelection(presenceId: Int, cellID: CellID, range: CellRange) extends KernelStatusUpdate {
  override def isRelevant(subscriber: Int): Boolean = presenceId != subscriber
}
object PresenceSelection extends KernelStatusUpdateCompanion[PresenceSelection](6)

final case class KernelError(err: Throwable) extends KernelStatusUpdate with AlwaysRelevant
object KernelError extends KernelStatusUpdateCompanion[KernelError](7) {
  implicit val codec: Codec[KernelError] = RuntimeError.throwableWithCausesCodec.xmap(new KernelError(_), _.err)
}
