package polynote.server.repository.format.ipynb

import cats.data.Ior
import cats.instances.either._
import cats.instances.list._
import cats.syntax.alternative._
import cats.syntax.either._
import io.circe._
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.syntax._
import polynote.data.Rope
import polynote.kernel.RuntimeError.RecoveredException
import polynote.kernel._
import polynote.messages._
import polynote.runtime.{MIMERepr, StringRepr}
import polynote.server.repository.NotebookContent

import scala.util.Try

sealed trait JupyterCellType

case object Markdown extends JupyterCellType
case object Code extends JupyterCellType

object JupyterCellType {
  implicit val encoder: Encoder[JupyterCellType] = Encoder.encodeString.contramap {
    case Markdown => "markdown"
    case Code => "code"
  }

  implicit val decoder: Decoder[JupyterCellType] = Decoder.decodeString.emap {
    case "markdown" => Right(Markdown)
    case "code"     => Right(Code)
    case other      => Left(s"Invalid cell type $other")
  }
}

sealed trait JupyterOutput

object JupyterOutput {

  final case class Stream(
    name: String,
    text: List[String]
  ) extends JupyterOutput

  final case class DisplayData(
    data: Map[String, Json],
    metadata: Option[JsonObject]
  ) extends JupyterOutput

  final case class ExecuteResult(
    execution_count: Int,
    data: Map[String, Json],
    metadata: Option[JsonObject]
  ) extends JupyterOutput

  final case class Error(
    ename: String,
    evalue: String,
    traceback: List[String]
  ) extends JupyterOutput

  import io.circe.generic.extras, extras.Configuration
  implicit val conf: Configuration = Configuration.default.withDiscriminator("output_type").withSnakeCaseConstructorNames.withSnakeCaseMemberNames

  implicit val encoder: Encoder[JupyterOutput] = extras.semiauto.deriveEncoder[JupyterOutput]
  implicit val decoder: Decoder[JupyterOutput] = extras.semiauto.deriveDecoder[JupyterOutput]

  def toResult(cellId: CellID)(result: JupyterOutput): Result = {
    def jsonToStr(json: Json): String = json.fold("", _.toString, _.toString, _.toString, _.map(jsonToStr).mkString, _.toString)

    def convertData(data: Map[String, Json], metadata: Option[JsonObject]) = metadata.flatMap(_("rel").flatMap(_.asString)) match {
      case Some("compiler_errors") => data("application/json").as[CompileErrors].fold(_ => CompileErrors(Nil), identity)
      case _ =>

        val relStr = (
          for {
            json <- metadata.toSeq
            k <- json.keys
            jsonV <- json(k)
            v <- jsonV.asString
          } yield s"$k=$v"
        ).mkString("; ", " ", "")

        data.head match {
          case (mime, content) => Output(s"$mime$relStr", jsonToStr(content))
        }
    }

    result match {
      case Stream(name, text) => Output(s"text/plain; rel=$name", text.mkString)
      case DisplayData(data, metadata) => convertData(data, metadata)
      case ExecuteResult(execId, data, metadata) =>
        val meta  = metadata.map(_.toMap).getOrElse(Map.empty)
        // maybe it's a CompileError?
        meta.get("rel").find(_.asString.exists(_ == "compiler_errors")).fold[Result] {
          // nope, it's just a normal result
          val name  = meta.get("name").flatMap(_.asString).getOrElse("Out")
          val typ   = meta.get("type").flatMap(_.asString).getOrElse("")
          val reprs = data.toList.map {
            case ("text/plain", json) => StringRepr(jsonToStr(json))
            case (mime, json) => MIMERepr(mime, jsonToStr(json))
          }
          ResultValue(name, typ, reprs, cellId, (), scala.reflect.runtime.universe.NoType, None, live = false)
        } { _ =>
          // yep, it's a CompileError!
          data.get("application/json")
            .flatMap(_.as[CompileErrors].fold(_ => None, Option(_)))
            .getOrElse(CompileErrors(Nil))
        }
      case Error(typ, message, trace) =>
        val traceElements = trace.flatMap(el => {
          Try {
            val Array(declaringClass, methodName, fileName, lineNumber) = el.split(",")
            new StackTraceElement(declaringClass, methodName, fileName, lineNumber.toInt)
          }.toOption
        }).toArray
        val err = RecoveredException(message, typ)
        err.setStackTrace(traceElements)
        RuntimeError(err)
    }
  }

  def fromResult(result: Result, execId: Int): Seq[JupyterOutput] = result match {
    case Output(contentType, content) =>
      val (mime, args) = Output.parseContentType(contentType)

      List {
        args.get("rel") match {
          case Some(name) if mime == "text/plain" && name == "stdout" => Stream(name, content.toList)
          case Some(name) if mime == "text/plain" && name == "stderr" => Stream(name, content.toList)
          case _ => DisplayData(Map(mime -> Json.arr(content.map(_.asJson): _*)), args.get("lang").map(l => Map("lang" -> l).asJsonObject))
        }
      }

    case e @ CompileErrors(errs) =>
      ExecuteResult(
        execId,
        Map(
          "application/json" -> e.asJson,
          "text/plain" -> Json.arr(errs.map(_.toString).map(Json.fromString): _*)),
        Some(JsonObject("rel" -> Json.fromString("compiler_errors")))) :: Nil

    case e @ RuntimeError(err) =>
      val (typ, msg) = err match {
        case RecoveredException(msg, typ) => (typ, msg)
        case err => (err.getClass.getName, err.getMessage)
      }
      Error(typ, Option(msg).getOrElse(""), err.getStackTrace.map(el => Seq(el.getClassName, el.getMethodName, el.getFileName, el.getLineNumber).mkString(",")).toList) :: Nil

    case ClearResults() => Nil
    case rv @ ResultValue(name, typeName, reprs, _, _, _, _, _) if rv.isCellResult =>

      reprs.collect {
        case StringRepr(str) => "text/plain" -> Json.arr(str.linesWithSeparators.toSeq.map(_.asJson): _*)
        case MIMERepr(mimeType, content) => mimeType -> Json.arr(content.linesWithSeparators.toSeq.map(_.asJson): _*)
      } match {
        case results =>
          val meta = List(
            Option(name).filterNot(_.isEmpty).map(name => "name" -> name.asJson),
            Some("type" -> typeName.asJson)
          ).flatten

          List(ExecuteResult(execId, results.toMap, Some(JsonObject(meta: _*))))
      }

    case ResultValue(_, _, _, _, _, _, _, _) => Nil
    case ExecutionInfo(_, _) => Nil
  }
}

final case class JupyterCell(
  cell_type: JupyterCellType,
  execution_count: Option[Int],
  metadata: Option[JsonObject],
  language: Option[String],
  source: List[String],
  outputs: Option[List[JupyterOutput]]
)

object JupyterCell {
  implicit val encoder: ObjectEncoder[JupyterCell] = deriveEncoder[JupyterCell].contramapObject[JupyterCell] {
    cell =>
      if (cell.metadata.isEmpty)
        cell.copy(metadata = Some(cell.language.map(lang => JsonObject.singleton("language", lang.asJson)).getOrElse(JsonObject.empty)))
      else
        cell
  }

  implicit val decoder: Decoder[JupyterCell] = deriveDecoder[JupyterCell]

  def toNotebookCell(defaultLanguage: Option[String])(cell: JupyterCell, index: Int): NotebookCell = {
    val language = cell.cell_type match {
      case Markdown => "text"
      case Code     => cell.language orElse cell.metadata.flatMap(_("language").flatMap(_.asString)) orElse defaultLanguage getOrElse "scala"
    }

    val (meta, comments) = cell.metadata.map {
      obj =>
        val disabled = obj("cell.metadata.run_control.frozen").flatMap(_.asBoolean).getOrElse(false)
        val hideSource = obj("jupyter.source_hidden").flatMap(_.asBoolean).getOrElse(false)
        val hideOutput = obj("jupyter.outputs_hidden").flatMap(_.asBoolean).getOrElse(false)
        val wrapOutput = obj("cell.metadata.wrap_output").flatMap(_.asBoolean).getOrElse(false)
        val executionInfo = obj("cell.metadata.exec_info").flatMap(_.as[ExecutionInfo].right.toOption)
        val comments = obj("cell.comments").flatMap(_.as[ShortMap[CommentID, Comment]].right.toOption).getOrElse(ShortMap(Map.empty[CommentID, Comment])) // TODO: should we verify identity?

        (CellMetadata(disabled, hideSource, hideOutput, wrapOutput, executionInfo), comments)
    }.getOrElse((CellMetadata(), ShortMap(Map.empty[CommentID, Comment])))

    NotebookCell(index, language, Rope(cell.source.mkString), ShortList(cell.outputs.getOrElse(Nil).map(JupyterOutput.toResult(index))), meta, comments)
  }

  def fromNotebookCell(cell: NotebookCell): JupyterCell = {
    val executionCount = Option(cell.id.toInt) // TODO: do we need the real exec id?

    val contentLines = cell.content.toString.linesWithSeparators.toList
    val cellType = cell.language.toString match {
      case "text" | "markdown" => Markdown
      case _ => Code
    }

    val meta = cell.metadata match {
      case CellMetadata(disableRun, hideSource, hideOutput, wrapOutput, executionInfo) =>
        val runControl = if (disableRun) List("cell.metadata.run_control.frozen" -> Json.fromBoolean(disableRun)) else Nil
        val source = if (hideSource) List("jupyter.source_hidden" -> Json.fromBoolean(hideSource)) else Nil
        val output = if (hideOutput) List("jupyter.outputs_hidden" -> Json.fromBoolean(hideOutput)) else Nil
        val execInfo =  if (executionInfo.isDefined) List("cell.metadata.exec_info" -> executionInfo.asJson, "language" -> cell.language.toString.asJson) else Nil
        val comments = if (cell.comments.nonEmpty) List("cell.comments" -> cell.comments.asJson) else Nil
        val wrapped = if (cell.metadata.wrapOutput) List("cell.metadata.wrap_output" -> Json.fromBoolean(wrapOutput)) else Nil
        val metadata = runControl ++ source ++ output ++ execInfo ++ comments ++ wrapped
        if (metadata.nonEmpty) Option(JsonObject.fromMap(metadata.toMap)) else None
    }

    val outputs = cell.results.flatMap(JupyterOutput.fromResult(_, executionCount.getOrElse(-1)))

    val (streams, others) = outputs.collect {
      case stream@JupyterOutput.Stream(_, _) => Either.left(stream)
      case other => Either.right(other)
    }.separate

    val groupedStreams = streams.groupBy(_.name).toList.map {
      case (name, chunks) => JupyterOutput.Stream(name, chunks.flatMap(_.text))
    }

    JupyterCell(cellType, executionCount, meta, Some(cell.language), contentLines, Some(groupedStreams ::: others))
  }
}

final case class JupyterNotebook(
  metadata: Option[JsonObject],
  nbformat: Int = 4,
  nbformat_minor: Int = 0,
  cells: List[JupyterCell]
)

object JupyterNotebook {
  implicit val encoder: Encoder[JupyterNotebook] = deriveEncoder[JupyterNotebook]
  implicit val decoder: Decoder[JupyterNotebook] = deriveDecoder[JupyterNotebook]

  def toNotebook(notebook: JupyterNotebook): NotebookContent = {
    val config = notebook.metadata.flatMap(_("config")).flatMap(_.as[NotebookConfig].right.toOption)
    val notebookLanguage = notebook.metadata.flatMap {
      meta => HCursor.fromJson(Json.fromJsonObject(meta)).downField("language_info").downField("name").as[String].toOption
    }
    val cells = ShortList(notebook.cells.zipWithIndex.map((JupyterCell.toNotebookCell(notebookLanguage) _).tupled))
    NotebookContent(cells, config)
  }

  val excludedLanguages: Set[String] = Set("text", "markdown", "html", "viz")

  def fromNotebook(notebook: NotebookContent): JupyterNotebook = {
    val languages = notebook.cells.collect {
      case cell if !excludedLanguages(cell.language) => cell.language
    }

    val mostUsedLanguage = languages.groupBy(identity)
      .mapValues(_.size)
      .toSeq
      .sortBy {
        case (lang, count) => -count
      }.map(_._1).headOption.getOrElse("scala")

    val languageInfo = Json.fromJsonObject(JsonObject.singleton("name", Json.fromString(mostUsedLanguage)))

    val meta = JsonObject(
      "config" -> notebook.config.map(_.asJson).getOrElse(Json.Null),
      "language_info" -> languageInfo)
    val cells = notebook.cells.map(JupyterCell.fromNotebookCell)
    JupyterNotebook(metadata = Some(meta), cells = cells)
  }
}

final case class JupyterWorksheetV3(
  cells: List[JupyterCell],
  metadata: Option[JsonObject]
)

object JupyterWorksheetV3 {
  // some JSON transformations to nbformat 3
  implicit val encoder: Encoder[JupyterWorksheetV3] = deriveEncoder[JupyterWorksheetV3].mapJsonObject {
    obj =>
      val transformedCells = for {
        cellsJson <- obj("cells")
        cellsList <- cellsJson.asArray
      } yield for {
        cellJson <- cellsList
      } yield cellJson.mapObject {
        cellObj => JsonObject.fromMap {
          cellObj.toMap.map {
            case ("source", source) => ("input", source)
            case ("execution_id", id) => ("prompt_number", id)
            case ("outputs", outputs) => "outputs" -> outputs.mapArray {
              _.map {
                _.mapObject {
                  outputObj =>
                    val typ = outputObj("output_type").getOrElse("")
                    JsonObject.fromMap {
                      outputObj.toMap.map {
                        case ("name", name) if typ == "stream" => ("stream", name)
                        case ("execution_id", id) => ("prompt_number", id)
                        case ("data", data) => "data" -> data.mapObject {
                          dataObj => JsonObject.fromMap{
                            dataObj.toMap.map {
                              case ("application/json", json) => ("application/json", json.noSpaces.asJson)
                              case kv => kv
                            }
                          }
                        }
                        case kv => kv
                      }
                    }
                }
              }
            }
            case kv => kv
          }
        }
      }
      obj.remove("cells").add("cells", transformedCells.map(v => Json.arr(v: _*)).getOrElse(Json.Null))
  }

  implicit val decoder: Decoder[JupyterWorksheetV3] = deriveDecoder[JupyterWorksheetV3].prepare {
    cursor =>
      cursor.downField("cells").downArray.withFocus {
        _.mapArray {
          cells => cells.map {
            cell => cell.mapObject {
              cellObj => JsonObject.fromMap {
                cellObj.toMap.map {
                  case ("input", source) => ("source", source)
                  case ("prompt_number", id) => ("execution_id", id)
                  case ("outputs", outputs) => "outputs" -> outputs.mapArray {
                    _.map {
                      _.mapObject {
                        outputObj => JsonObject.fromMap{
                          outputObj.toMap.map {
                            case ("stream", name) => ("name", name)
                            case ("prompt_number", id) => ("execution_id", id)
                            case ("data", data) => "data" -> data.mapObject {
                              dataObj => JsonObject.fromMap {
                                dataObj.toMap.map {
                                  case ("application/json", jsonStr) => "application/json" -> io.circe.parser.parse(jsonStr.asString.getOrElse("")).right.getOrElse(jsonStr)
                                  case kv => kv
                                }
                              }
                            }
                            case kv => kv
                          }
                        }
                      }
                    }
                  }
                  case kv => kv
                }
              }
            }
          }
        }
      }.up.up
  }
}

final case class JupyterNotebookV3(
  metadata: Option[JsonObject],
  nbformat: Int = 3,
  nbformat_minor: Int = 0,
  worksheets: List[JupyterWorksheetV3]
)

object JupyterNotebookV3 {
  implicit val encoder: Encoder[JupyterNotebookV3] = deriveEncoder[JupyterNotebookV3]
  implicit val decoder: Decoder[JupyterNotebookV3] = deriveDecoder[JupyterNotebookV3]

  def fromV4(v4: JupyterNotebook): JupyterNotebookV3 = v4 match {
    case JupyterNotebook(metadata, _, _, cells) => JupyterNotebookV3(metadata = metadata, worksheets = List(JupyterWorksheetV3(cells, None)))
  }

  def toV4(v3: JupyterNotebookV3): JupyterNotebook = v3 match {
    case JupyterNotebookV3(metadata, _, _, worksheets) => JupyterNotebook(metadata = metadata, cells = worksheets.flatMap(_.cells))
  }
}

final case class JupyterNotebookStaged(
  metadata: Option[JsonObject],
  nbformat: Int,
  nbformat_minor: Int
)

object JupyterNotebookStaged {
  implicit val decoder: Decoder[JupyterNotebookStaged] = deriveDecoder[JupyterNotebookStaged]
}