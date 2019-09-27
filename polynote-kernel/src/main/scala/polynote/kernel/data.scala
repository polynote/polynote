package polynote.kernel

import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder}
import polynote.messages.{CellID, ShortString, TinyList, TinyMap, TinyString}
import scodec.codecs.{Discriminated, Discriminator, byte}
import scodec.{Attempt, Codec, Err}
import scodec.codecs.implicits._
import shapeless.cachedImplicit

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
  completionType: CompletionType)

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

sealed trait KernelStatusUpdate

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
) extends KernelStatusUpdate

object UpdatedSymbols extends KernelStatusUpdateCompanion[UpdatedSymbols](0)

sealed trait TaskStatus {
  def isDone: Boolean
}

object TaskStatus {
  sealed trait DoneStatus extends TaskStatus { final val isDone: Boolean = true }
  sealed trait NotDoneStatus extends TaskStatus { final val isDone: Boolean = false }

  final case object Complete extends DoneStatus
  final case object Queued extends NotDoneStatus
  final case object Running extends NotDoneStatus
  final case object Error extends DoneStatus

  val fromByte: PartialFunction[Byte, TaskStatus] = {
    case 0 => Complete
    case 1 => Running
    case 2 => Queued
    case 3 => Error
  }

  def toByte(taskStatus: TaskStatus): Byte = taskStatus match {
    case Complete => 0
    case Running => 1
    case Queued => 2
    case Error => 3
  }

  implicit val codec: Codec[TaskStatus] = byte.exmap(
    fromByte.lift andThen (Attempt.fromOption(_, Err("Invalid task status byte"))),
    s => Attempt.successful(toByte(s))
  )

  implicit val ordering: Ordering[TaskStatus] = new Ordering[TaskStatus] {
    // this isn't the most concise comparison routine, but it should give compiler warnings if any statuses are added w/o being handled
    def compare(x: TaskStatus, y: TaskStatus): Int = (x, y) match {
      case (Complete, Complete) | (Running, Running) | (Queued, Queued) | (Error, Error) => 0
      case (Complete, _) => 1
      case (_, Complete) => -1
      case (Queued, _)   => -1
      case (_, Queued)   => 1
      case (Error, _)    => 1
      case (_, Error)    => -1
    }
  }
}

final case class TaskInfo(
  id: TinyString,
  label: TinyString,
  detail: ShortString,
  status: TaskStatus,
  progress: Byte = 0) {

  def running: TaskInfo = copy(status = TaskStatus.Running)
  def completed: TaskInfo = copy(status = TaskStatus.Complete, progress = 255.toByte)
  def failed: TaskInfo = if (status == TaskStatus.Complete) this else copy(status = TaskStatus.Error, progress = 255.toByte)
  def done(status: TaskStatus.DoneStatus): TaskInfo = if (this.status.isDone) this else copy(status = status, progress = 255.toByte)
  def progress(fraction: Double): TaskInfo = copy(progress = (fraction * 255).toByte)
  def progress(fraction: Double, detailOpt: Option[String]): TaskInfo = copy(progress = (fraction * 255).toByte, detail = detailOpt.getOrElse(detail))
  def progressFraction: Double = progress.toDouble / 255
}

object TaskInfo {
  def apply(id: String): TaskInfo = TaskInfo(id, "", "", TaskStatus.Queued)
}

final case class UpdatedTasks(
  tasks: TinyList[TaskInfo]
) extends KernelStatusUpdate

object UpdatedTasks extends KernelStatusUpdateCompanion[UpdatedTasks](1) {
  def one(info: TaskInfo): UpdatedTasks = UpdatedTasks(List(info))
}

final case class KernelBusyState(busy: Boolean, alive: Boolean) extends KernelStatusUpdate {
  def setBusy: KernelBusyState = copy(busy = true)
  def setIdle: KernelBusyState = copy(busy = false)
  def setAlive: KernelBusyState = copy(alive = true)
  def setDead: KernelBusyState = copy(alive = false)
}
object KernelBusyState extends KernelStatusUpdateCompanion[KernelBusyState](2)

//                                           key          html
final case class KernelInfo(content: TinyMap[ShortString, String]) extends KernelStatusUpdate {
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

final case class ExecutionStatus(cellID: CellID, pos: Option[(Int, Int)]) extends KernelStatusUpdate
object ExecutionStatus extends KernelStatusUpdateCompanion[ExecutionStatus](4)