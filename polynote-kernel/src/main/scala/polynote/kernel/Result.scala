package polynote.kernel

import java.nio.charset.{Charset, StandardCharsets}

import scodec.{Attempt, Codec, DecodeResult, Err}
import scodec.codecs._
import scodec.codecs.implicits._
import shapeless.cachedImplicit
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import polynote.messages.{CellID, NotebookCell, ShortList, TinyList, TinyString, iorCodec, tinyListCodec, tinyStringCodec, truncateTinyString}
import polynote.runtime.{CellRange, ValueRepr}
import scala.collection.mutable.ListBuffer
import scala.reflect.api.Universe

sealed abstract class ResultCompanion[T <: Result](msgId: Byte) {
  implicit val discriminator: Discriminator[Result, T, Byte] = Discriminator(msgId)
}

final case class Output(contentType: String, content: Vector[String]) extends Result
object Output extends ResultCompanion[Output](0) {
  def split(str: String): Vector[String] = {
    var start = 0
    var pos = 0
    val len = str.length()
    var result = Vector.empty[String]
    while (pos < len) {
      if (str.charAt(pos) == '\n') {
        result = result :+ str.substring(start, pos + 1)
        start = pos + 1
      }
      pos += 1
    }
    if (pos > start) {
      result = result :+ str.substring(start, pos)
    }
    result
  }

  def apply(contentType: String, content: String): Output = Output(contentType, split(content))

  def parseContentType(contentType: String): (String, Map[String, String]) = contentType.split(';').toList match {
    case Nil => ("", Map.empty)
    case mime :: Nil  => mime -> Map.empty
    case mime :: args => mime -> args.map(_.trim).flatMap {
      arg => arg.indexOf('=') match {
        case -1 => None
        case n  => Some{
          arg.splitAt(n) match {
            case (k, v) => k -> v.stripPrefix("=")
          }
        }
      }
    }.toMap
  }


}

final case class CompileErrors(
  reports: List[KernelReport]
) extends Throwable(("Compile errors:" +: reports.filter(_.severity == 2).map(_.msg)).mkString("\n -")) with Result

object CompileErrors extends ResultCompanion[CompileErrors](1) {
  implicit val encoder: Encoder[CompileErrors] = Encoder.encodeList[KernelReport].contramap(_.reports)
  implicit val decoder: Decoder[CompileErrors] = Decoder.decodeList[KernelReport].map(CompileErrors(_))
}

final case class RuntimeError(err: Throwable) extends Throwable(s"${err.getClass.getSimpleName}: ${err.getMessage}", err) with Result

case object EmptyCell extends Throwable

object RuntimeError extends ResultCompanion[RuntimeError](2) {

  private implicit val charset: Charset = StandardCharsets.UTF_8

  val str = variableSizeBytes(int32, string)

  // TODO: factor out these Throwable codecs for reuse
  val traceCodec: Codec[StackTraceElement] = (str ~ str ~ str ~ int32).xmap(
    _ match {
      case (((cls, method), file), line) => new StackTraceElement(cls, method, file, line)
    },
    el => (((el.getClassName, Option(el.getMethodName).getOrElse("")), Option(el.getFileName).getOrElse("")), el.getLineNumber)
  )

  val throwableCodec: Codec[Throwable] = (str ~ str ~ listOfN(uint16, traceCodec)).xmap(
    _ match {
      case ((typ, msg), trace) =>
        val err = RecoveredException(Option(msg).getOrElse(""), typ)
        err.setStackTrace(trace.toArray)
        err
    },
    _ match {
      case err @ RecoveredException(msg, typ) => ((typ, Option(msg).getOrElse("")), err.getStackTrace.toList)
      case err => ((err.getClass.getName, Option(err.getMessage).getOrElse("")), err.getStackTrace.toList)
    }

  )

  final case class RecoveredException(msg: String, originalType: String) extends RuntimeException(s"$originalType: $msg")

  val throwableWithCausesCodec: Codec[Throwable] = {
    def collapse(errs: List[Throwable]): Attempt[Throwable] = errs match {
      case head :: tail =>
        var current = head

        tail.foreach {
          err =>
            current.initCause(err)
            current = err
        }
        Attempt.successful(head)
      case Nil => Attempt.failure(Err("Empty list of errors"))
    }

    def expand(err: Throwable): Attempt[List[Throwable]] = {
      if (err == null)
        return Attempt.failure(Err("Null exception"))

      val causes = ListBuffer(err)

      var current = err.getCause match {
        case `err` => null
        case cause => cause
      }

      var n = 0
      while (n < 16 && current != null) {
        causes += current
        current = current.getCause match {
          case cause if cause eq current => null
          case cause => cause
        }
        n += 1
      }

      Attempt.successful(causes.toList)
    }

    listOfN(uint8, throwableCodec).exmap(collapse, expand)
  }

  implicit val codec: Codec[RuntimeError] = throwableWithCausesCodec.as[RuntimeError]
}

object ErrorResult {
  def apply(err: Throwable): Result with Throwable = err match {
    case e @ CompileErrors(_) => e
    case RuntimeError(e @ RuntimeError(_)) => apply(e)  // in case we accidentally nested RuntimeErrors
    case e @ RuntimeError(_) => e
    case e => RuntimeError(e)
  }
}


final case class ClearResults() extends Result

object ClearResults extends ResultCompanion[ClearResults](3)


// TODO: fix the naming of these classes
final case class ResultValue(
  name: TinyString,
  typeName: TinyString,
  reprs: TinyList[ValueRepr],
  sourceCell: CellID,
  value: Any,
  scalaType: Universe#Type,
  pos: Option[CellRange],
  live: Boolean = true
) extends Result {

  def isCellResult: Boolean = name == "Out"

}

object ResultValue extends ResultCompanion[ResultValue](4) {
  // manual codec - we'll never encode nor decode `value` nor `scalaType`.
  implicit val codec: Codec[ResultValue] =
    (tinyStringCodec ~ tinyStringCodec ~ tinyListCodec(ValueReprCodec.codec) ~ short16 ~ optional(bool(8), int32 ~ int32) ~ bool(8)).xmap(
      {
        case (((((name, typeName), reprs), sourceCell), optPos), live) => ResultValue(name, typeName, reprs, sourceCell, (), scala.reflect.runtime.universe.NoType, optPos, live)
      },
      v => (((((v.name, v.typeName), v.reprs), v.sourceCell), v.pos),  v.live)
    )
}

final case class ExecutionInfo(startTs: Long, endTs: Option[Long]) extends Result

object ExecutionInfo extends ResultCompanion[ExecutionInfo](5) {
  implicit val encoder: Encoder[ExecutionInfo] = deriveEncoder[ExecutionInfo]
  implicit val decoder: Decoder[ExecutionInfo] = deriveDecoder[ExecutionInfo]
}


sealed trait Result {
  def toCellUpdate: NotebookCell => NotebookCell = Result.toCellUpdate(this)
}

object Result {
  implicit val discriminated: Discriminated[Result, Byte] = Discriminated(byte)
  implicit val codec: Codec[Result] = cachedImplicit

  def toCellUpdate(result: Result): NotebookCell => NotebookCell = {
    // process carriage returns in the line – a carriage return deletes anything before it in the line, unless
    // it's the last character before the linefeed (which must be the final character)
    def collapseCrs(line: String): String = line.lastIndexOf('\r', line.length - 3) match {
      case -1 => line
      case n  => line.drop(n + 1)
    }

    result match {
      case ClearResults() => _.copy(results = ShortList(Nil))
      case execInfo@ExecutionInfo(_, _) => cell => cell.copy(results = ShortList(cell.results :+ execInfo), metadata = cell.metadata.copy(executionInfo = Some(execInfo)))
      case Output(contentType, lines) if contentType.startsWith("text/plain") && lines.nonEmpty =>
        val processedTail = lines.tail.map(collapseCrs)

        cell => {
          val updatedResults = cell.results.lastOption match {
            case Some(Output(`contentType`, linesPrev)) =>
              val combinedLines = if (linesPrev.nonEmpty && !linesPrev.last.endsWith("\n")) {
                linesPrev.dropRight(1) ++ (collapseCrs(linesPrev.last + lines.head) +: processedTail)
              } else {
                linesPrev ++ (collapseCrs(lines.head) +: processedTail)
              }
              cell.results.dropRight(1) :+ Output(contentType, combinedLines)
            case _ => cell.results :+ result
          }
          cell.copy(results = ShortList.fromRight(updatedResults))
        }
      case result => cell => cell.copy(results = ShortList.fromRight(cell.results :+ result))
    }
  }
}