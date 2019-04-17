package polynote.server.repository.ipynb

import io.circe.{Decoder, Json, JsonObject}
import io.circe.syntax._
import io.circe.generic.semiauto._
import polynote.server.repository.ipynb.JupyterOutput.{DisplayData, Error, ExecuteResult}

// Conversion for Zeppelin notebooks. Lossy, only supports decoding.

final case class ZeppelinNotebook(
  paragraphs: List[ZeppelinParagraph],
  name: String
) {
  def toJupyterNotebook: JupyterNotebook = JupyterNotebook(None, cells = paragraphs.map(_.toJupyterCell))
}

object ZeppelinNotebook {
  implicit val decoder: Decoder[ZeppelinNotebook] = deriveDecoder[ZeppelinNotebook]
}

final case class ZeppelinParagraph(
  text: Option[String],
  results: Option[ZeppelinResult],
  status: ZeppelinStatus,
  language: String
) {
  def toJupyterCell: JupyterCell = {
    val (cellType, res) = language match {
      case "markdown" => (Markdown, None)
      case "text"     => (Markdown, None)
      case _          => (Code, results.map(_.toJupyterOutput))
    }

    val source = text.toList.flatMap(_.linesWithSeparators) match {
      // drop the first line if it's a magic because we don't need it
      case firstLine :: rest if firstLine.startsWith("%") => rest
      case other => other
    }

    JupyterCell(
      cell_type = cellType,
      execution_count = None,
      metadata = None, //TODO: add exec info
      language = Option(language),
      source = source,
      outputs = res
    )
  }
}

object ZeppelinParagraph {
  implicit val decoder: Decoder[ZeppelinParagraph] = deriveDecoder[ZeppelinParagraph].prepare {
    cursor =>
      // lift paragraph.config.editorSetting.language to top-level
      // TODO: is this language guaranteed to be present and correct? If not, we might need to parse magics :(
      cursor.downField("config").downField("editorSetting").downField("language").focus.map {
        lang =>
          cursor.withFocus(_.deepMerge(JsonObject.fromMap(Map("language" -> lang)).asJson))
      }.getOrElse(cursor)
  }
}

final case class ZeppelinResult(
  code: ZeppelinResultCode,
  msg: List[ZeppelinOutput]
){

  def toJupyterOutput: List[JupyterOutput] = msg.zipWithIndex.flatMap { case (output, idx) =>
    output.toJupyterOutput(code, idx)
  }
}

object ZeppelinResult {
  implicit val decoder: Decoder[ZeppelinResult] = deriveDecoder[ZeppelinResult]
}

final case class ZeppelinOutput(
  `type`: ZeppelinResultType,
  data: String
) {
  import ZeppelinResultCode._
  import ZeppelinResultType._

  def toJupyterOutput(code: ZeppelinResultCode, idx: Int): Option[JupyterOutput] = code match {
    case SUCCESS =>

      def successfulExecRes(data: Map[String, Json]) = Option(ExecuteResult(
          execution_count = idx,
          data = data,
          metadata = None
        ))

      `type` match {
        case TEXT => successfulExecRes(
          Map("text/plain" -> Json.arr(data.linesWithSeparators.toSeq.map(_.asJson): _*))
        )
        case HTML => successfulExecRes(
          Map("text/html" -> Json.fromString(data.stripPrefix("%html ")))
        )
        case ANGULAR => successfulExecRes(
          Map("application/javascript" -> Json.fromString(data))
        )
        case TABLE => Option(DisplayData(
          data = Map("text/html" -> Json.arr(Json.fromString(zepTableHTML(data)))),
          metadata = None
        ))
        case IMG => // looks like zep encodes images in base64 though I've never seen this type used
          Option(DisplayData(
            data = Map("text/html" -> Json.arr(Json.fromString(s"""<img src="data:image/jpeg;base64, ${data.stripPrefix("%img").trim}"/>"""))),
            metadata = None
          ))
        case SVG =>
          Option(DisplayData(
            data = Map("image/svg+xml" -> Json.fromString(data)),
            metadata = None
          ))
        case NULL => None
      }

    case INCOMPLETE => None
    case ERROR =>

      val iter = data.lines
      val firstException = iter.next().split(":")

      Option(Error(
        // TODO: can we do anything better here?
        ename = firstException.headOption.getOrElse(s"Unknown Imported Zeppelin Exception: $data").trim,
        evalue = firstException.lastOption.getOrElse(s"Unknown Imported Zeppelin Exception: $data").trim,
        traceback = iter.toList.map(_.replace("\t", "  "))
      ))
    case KEEP_PREVIOUS_RESULT => None
  }

  def zepTableHTML(zepData: String): String = {
    val itr = zepData.lines
    val header = itr.next().split("\t")
    val headerHTML = header.mkString("<th>", "</th><th>", "</th>")
    val rowsHTML = itr.toSeq.map(_.split("\t").mkString("<td>", "</td><td>", "</td>")).mkString("<tr>", "</tr><tr>", "</tr>")
    s"<table><tr>$headerHTML</tr>$rowsHTML</table>"
  }
}

object ZeppelinOutput {
  implicit val decoder: Decoder[ZeppelinOutput] = deriveDecoder[ZeppelinOutput]
}

// See: https://github.com/apache/zeppelin/blob/master/zeppelin-interpreter/src/main/java/org/apache/zeppelin/scheduler/Job.java#L47-L53
sealed trait ZeppelinStatus
object ZeppelinStatus {
  case object UNKNOWN extends ZeppelinStatus
  case object READY extends ZeppelinStatus
  case object PENDING extends ZeppelinStatus
  case object RUNNING extends ZeppelinStatus
  case object FINISHED extends ZeppelinStatus
  case object ERROR extends ZeppelinStatus
  case object ABORT extends ZeppelinStatus

  implicit val decoder: Decoder[ZeppelinStatus] = Decoder.decodeString.map {
    case "UNKNOWN"  => UNKNOWN
    case "READY"    => READY
    case "PENDING"  => PENDING
    case "RUNNING"  => RUNNING
    case "FINISHED" => FINISHED
    case "ERROR"    => ERROR
    case "ABORT"    => ABORT
  }
}

// See: https://github.com/apache/zeppelin/blob/master/zeppelin-interpreter/src/main/java/org/apache/zeppelin/interpreter/InterpreterResult.java#L40-L45
sealed trait ZeppelinResultCode
object ZeppelinResultCode {
  case object SUCCESS extends ZeppelinResultCode
  case object INCOMPLETE extends ZeppelinResultCode
  case object ERROR extends ZeppelinResultCode
  case object KEEP_PREVIOUS_RESULT extends ZeppelinResultCode

  implicit val decoder: Decoder[ZeppelinResultCode] = Decoder.decodeString.map {
    case "SUCCESS"              => SUCCESS
    case "INCOMPLETE"           => INCOMPLETE
    case "ERROR"                => ERROR
    case "KEEP_PREVIOUS_RESULT" => KEEP_PREVIOUS_RESULT
  }
}

// See: https://github.com/apache/zeppelin/blob/master/zeppelin-interpreter/src/main/java/org/apache/zeppelin/interpreter/InterpreterResult.java#L50-L59
sealed trait ZeppelinResultType
object ZeppelinResultType {
  case object TEXT extends ZeppelinResultType
  case object HTML extends ZeppelinResultType
  case object ANGULAR extends ZeppelinResultType
  case object TABLE extends ZeppelinResultType
  case object IMG extends ZeppelinResultType
  case object SVG extends ZeppelinResultType
  case object NULL extends ZeppelinResultType
//  case object NETWORK extends ZeppelinResultType // looks like this is not supported in our version of zep?

  implicit val decoder: Decoder[ZeppelinResultType] = Decoder.decodeString.map {
    case "TEXT"    => TEXT
    case "HTML"    => HTML
    case "ANGULAR" => ANGULAR
    case "TABLE"   => TABLE
    case "IMG"     => IMG
    case "SVG"     => SVG
    case "NULL"    => NULL
//    case "NETWORK" => NETWORK
  }
}
