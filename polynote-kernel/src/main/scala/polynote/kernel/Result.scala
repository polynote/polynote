package polynote.kernel

import java.nio.charset.{Charset, StandardCharsets}

import scodec.{Attempt, Codec, DecodeResult, Err}
import scodec.codecs._
import scodec.codecs.implicits._
import shapeless.cachedImplicit
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import scodec.bits.BitVector

import scala.collection.mutable.ListBuffer

sealed abstract class ResultCompanion[T <: Result](msgId: Byte) {
  implicit val discriminator: Discriminator[Result, T, Byte] = Discriminator(msgId)
}

final case class Output(contentType: String, content: String) extends Result
object Output extends ResultCompanion[Output](0) {
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

  def of(err: Throwable): RuntimeError = err match {
    case e @ RuntimeError(_) => e
    case e => RuntimeError(e)
  }

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

  // extending only to access the protected constructor (which skips building a pointless stack trace)
  final case class RecoveredException(msg: String, originalType: String) extends RuntimeException(s"$originalType: $msg", null, false, false)

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


final case class ClearResults() extends Result

object ClearResults extends ResultCompanion[ClearResults](3)

sealed trait Result

object Result {
  implicit val discriminated: Discriminated[Result, Byte] = Discriminated(byte)
  implicit val codec: Codec[Result] = cachedImplicit
}
