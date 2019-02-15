package polynote.kernel.lang.sql

import java.io.File

import cats.instances.list._
import cats.effect.IO
import cats.syntax.traverse._
import cats.syntax.functor._
import fs2.concurrent.Enqueue
import org.apache.spark.sql.catalyst.parser.{SqlBaseBaseVisitor, SqlBaseParser}
import org.apache.spark.sql.catalyst.parser.SqlBaseParser.SingleStatementContext
import org.apache.spark.sql.{DataFrame, Dataset, SparkSession}
import polynote.kernel._
import polynote.kernel.lang.LanguageKernel
import polynote.kernel.util.{Publish, RuntimeSymbolTable}
import polynote.messages.{ShortString, TinyList, TinyString}

import scala.collection.mutable

class SparkSqlInterpreter(val symbolTable: RuntimeSymbolTable) extends LanguageKernel[IO] {

  def predefCode: Option[String] = None

  private val spark = SparkSession.builder().getOrCreate()
  import spark.implicits._
  private val parser = new Parser

  private lazy val datasetType = symbolTable.global.typeOf[Dataset[Any]].typeConstructor

  private def dataFrames[D <: Decl](symbols: Seq[D]) = symbols.collect {
    case rv if rv.scalaType.dealiasWiden.typeConstructor <:< datasetType => rv
  }.toList

  private def registerTempViews(identifiers: List[Parser.TableIdentifier]) = {
    val candidates = identifiers.collect {
      case Parser.TableIdentifier(None, name) => name
    }.toSet

    dataFrames(symbolTable.currentTerms).collect {
      case rv if candidates(rv.name.toString) =>
        val nameString = rv.name.toString
        IO.pure(rv.value.asInstanceOf[Dataset[_]]).flatMap(ds => IO(ds.createOrReplaceTempView(nameString)).as(nameString))
    }
  }

  private def dropTempViews(views: List[String]) = views.map(name => IO(spark.catalog.dropTempView(name))).sequence.as(())

  private def outputDataFrame(df: DataFrame, out: Enqueue[IO, Result]) = {
    // TODO: We should have a way of just sending raw tabular data to the client for it to display and maybe plot!
    //       But we'd have to write some actual content to the notebook file... maybe Output could take multiple
    //       representations of the content and each representation could be possibly written to the notebook and
    //       possibly sent to the client?
    //       Sending raw table data was the reason for starting DataType stuff in polynote-runtime, but I haven't done
    //       anything with it yet (JS)

    // TODO: When async/updatable outputs are supported, would be cool to support structured streaming stuff

    // this is really just placeholder... in the future the display should be much fancier than this
    val schema = df.schema

    def escape(str: String) = str.replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    val heading = schema.fields.map {
      field => s"<th>${escape(field.name)}</th>"
    }.mkString("<thead><tr>", "", "</tr></thead>")

    val count = df.count()
    val rows = df.take(50)

    val body = rows.view.map {
      row => row.toSeq.view.map(_.toString).map(escape).mkString("<td>", "</td><td>", "</td>")
    }.mkString("<tbody><tr>", "</tr><tr>", "</tr></tbody>")

    out.enqueue1(
      Output(
        "text/html",
        s"""<table>
           |  <caption>Showing ${rows.length} of $count rows</caption>
           |  $heading
           |  $body
           |</table>""".stripMargin
      )
    )
  }

  def runCode(
    cell: String,
    visibleSymbols: Seq[Decl],
    previousCells: Seq[String],
    code: String,
    out: Enqueue[IO, Result],
    statusUpdates: Publish[IO, KernelStatusUpdate]
  ): IO[Unit] = for {
    _           <- IO(symbolTable.global.demandNewCompilerRun())
    run          = new symbolTable.global.Run
    _           <- symbolTable.drain()
    parseResult <- IO.fromEither(parser.parse(cell, code).fold(Left(_), Right(_), (errs, _) => Left(errs)))
    resultDF    <- registerTempViews(parseResult.tableIdentifiers).sequence.bracket(_ => IO(spark.sql(code)))(dropTempViews)
    _           <- symbolTable.publish(this, cell)(symbolTable.global.TermName(s"res$cell"), resultDF, Some(symbolTable.global.typeOf[DataFrame]))
    _           <- outputDataFrame(resultDF, out)
  } yield ()

  def completionsAt(
    cell: String,
    visibleSymbols: Seq[Decl],
    previousCells: Seq[String],
    code: String,
    pos: Int
  ): IO[List[Completion]] = {

    def completeAtPos(statement: SingleStatementContext) = {
      val results = statement.accept(new CompletionVisitor(pos, visibleSymbols))
      results
    }

    for {
      parseResult <- IO.fromEither(parser.parse(cell, code).toEither)
    } yield completeAtPos(parseResult.statement)
  }

  def parametersAt(cell: String, visibleSymbols: Seq[Decl], previousCells: Seq[String], code: String, pos: Int): IO[Option[Signatures]] =
    IO(None) // TODO: could we generate parameter hints for spark's builtin functions?

  def init(): IO[Unit] = IO.unit

  def shutdown(): IO[Unit] = IO.unit

  private lazy val databaseNames = spark.catalog.listDatabases().map(_.name).collect()

  // TODO: could we generate parameter lists and such?
  private lazy val functionNames = spark.catalog.listFunctions().map(_.name).collect()

  private val databaseTableCache = new mutable.HashMap[String, List[String]]()
  private def tablesOf(db: String): List[String] = databaseTableCache.getOrElseUpdate(db, spark.catalog.listTables(db).collect().map(_.name).toList)

  private class CompletionVisitor(pos: Int, visibleSymbols: Seq[Decl]) extends SqlBaseBaseVisitor[List[Completion]] {
    override def defaultResult(): List[Completion] = Nil

    override def aggregateResult(aggregate: List[Completion], nextResult: List[Completion]): List[Completion] = aggregate ++ nextResult
    override def visitTableIdentifier(ctx: SqlBaseParser.TableIdentifierContext): List[Completion] = {
      if (pos >= ctx.getStart.getStartIndex && pos <= ctx.getStop.getStopIndex + 1) {
        val db = Option(ctx.db).map(_.getText).filterNot(_.isEmpty)
        val ident = Option(ctx.table).map(_.getText).getOrElse("")
        db.fold {
          val viewsAndTables = dataFrames(visibleSymbols).view.map(_.name.toString) ++ databaseNames
          val result = viewsAndTables.collect {
            case name if name startsWith ident => Completion(name, Nil, Nil, ShortString(""), CompletionType.Term)
          }.toList.sortBy(_.name: String)
          result
        } {
          dbName => tablesOf(dbName).collect {
            case table if table startsWith ident => Completion(table, Nil, Nil, ShortString(""), CompletionType.Term)
          }.sortBy(_.name: String)
        }
      } else super.visitTableIdentifier(ctx)
    }

    override def visitIdentifier(ctx: SqlBaseParser.IdentifierContext): List[Completion] = {
      if (pos > ctx.getStart.getStartIndex && pos <= ctx.getStop.getStopIndex + 1) {
        val part = ctx.getText
        val allIdentifiers = functionNames.view.map {
          name => Completion(name, Nil, TinyList(List(TinyList(List.empty))), ShortString(""), CompletionType.Method)
        } ++ dataFrames(visibleSymbols).view.map {
          rv => Completion(rv.name.toString, Nil, Nil, ShortString(""), CompletionType.Term)
        } ++ databaseNames.view.map {
          name => Completion(name, Nil, TinyList(List(TinyList(List.empty))), ShortString(""), CompletionType.Method)
        }
        allIdentifiers.filter(_.name startsWith part).sortBy(_.name: String).toList
      } else super.visitIdentifier(ctx)
    }
  }
}

object SparkSqlInterpreter {
  class Factory extends LanguageKernel.Factory[IO] {
    def languageName: String = "SQL"
    def apply(dependencies: List[(String, File)], symbolTable: RuntimeSymbolTable): LanguageKernel[IO] = new SparkSqlInterpreter(symbolTable)
  }

  def factory(): Factory = new Factory
}
