package polynote.kernel

import cats.effect.concurrent.Ref
import fs2.Stream
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder}
import polynote.messages.{CellResult, ShortString, TinyList, TinyMap, TinyString}
import scodec.codecs.{Discriminated, Discriminator, byte}
import scodec.{Attempt, Codec, Err}

import scala.reflect.internal.util.Position
import scala.util.Try

/**
  * The Kernel is expected to reference cells by their notebook ID; it will have a [[Ref]] to the [[polynote.messages.Notebook]].
  *
  * @see [[polynote.kernel.lang.LanguageInterpreter]]
  */
trait KernelAPI[F[_]] {

  def init: F[Unit]

  def shutdown(): F[Unit]

  def startInterpreterFor(id: String): F[Stream[F, Result]]

  def runCell(id: String): F[Stream[F, Result]]

  def runCells(ids: List[String]): F[Stream[F, CellResult]]

  def completionsAt(id: String, pos: Int): F[List[Completion]]

  def parametersAt(cell: String, offset: Int): F[Option[Signatures]]

  def currentSymbols(): F[List[ResultValue]]

  def currentTasks(): F[List[TaskInfo]]

  def idle(): F[Boolean]

  def info: F[Option[KernelInfo]]

}

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
  docString: Option[ShortString])

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

sealed trait TaskStatus
object TaskStatus {
  final case object Complete extends TaskStatus
  final case object Queued extends TaskStatus
  final case object Running extends TaskStatus
  final case object Error extends TaskStatus

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
}

final case class TaskInfo(
  id: TinyString,
  label: TinyString,
  detail: TinyString,
  status: TaskStatus,
  progress: Byte = 0)

final case class UpdatedTasks(
  tasks: TinyList[TaskInfo]
) extends KernelStatusUpdate

object UpdatedTasks extends KernelStatusUpdateCompanion[UpdatedTasks](1)

final case class KernelBusyState(busy: Boolean, alive: Boolean) extends KernelStatusUpdate
object KernelBusyState extends KernelStatusUpdateCompanion[KernelBusyState](2)

//                                           key          html
final case class KernelInfo(content: TinyMap[ShortString, String]) extends KernelStatusUpdate
object KernelInfo extends KernelStatusUpdateCompanion[KernelInfo](3)