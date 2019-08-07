package polynote.kernel.lang.sql

import java.io.File

import cats.instances.list._
import cats.effect.{ContextShift, IO}
import cats.syntax.traverse._
import cats.syntax.functor._
import fs2.Stream
import fs2.concurrent.{Enqueue, Queue}
import org.apache.spark.sql.catalyst.parser.{SqlBaseBaseVisitor, SqlBaseParser}
import org.apache.spark.sql.catalyst.parser.SqlBaseParser.SingleStatementContext
import org.apache.spark.sql.thief.SessionStateThief
import org.apache.spark.sql.{DataFrame, Dataset, SparkSession}
import polynote.config.PolyLogger
import polynote.kernel.{lang, _}
import polynote.kernel.dependency.{ClassLoaderDependencyProvider, CoursierFetcher, DependencyManagerFactory, DependencyProvider}
import polynote.kernel.lang.LanguageInterpreter
import polynote.kernel.lang.scal.ScalaInterpreter
import polynote.kernel.util.{CellContext, KernelContext, Publish}
import polynote.messages.{CellID, ShortString, TinyList, TinyString}

import scala.collection.mutable
import scala.util.{Failure, Success}

class SparkSqlInterpreter(val kernelContext: KernelContext) extends LanguageInterpreter[IO] {

  import kernelContext.global, kernelContext.implicits.executionContext

  private val logger = new PolyLogger

  Thread.currentThread().setContextClassLoader(kernelContext.classLoader)

  def predefCode: Option[String] = None

  private val spark = SparkSession.builder().getOrCreate()
  import spark.implicits._
  private val parser = new Parser

  private lazy val datasetType = global.typeOf[Dataset[Any]].typeConstructor

  private def dataFrames(symbols: Seq[ResultValue]) = symbols.collect {
    case rv@ResultValue(_, _, _, _, _, scalaType: global.Type, _) if scalaType.typeConstructor <:< datasetType => rv
  }.toList

  private def registerTempViews(identifiers: List[Parser.TableIdentifier], cellContext: CellContext) = {
    val candidates = identifiers.collect {
      case Parser.TableIdentifier(None, name) => name
    }.toSet

    dataFrames(cellContext.visibleValues).collect {
      case rv if candidates(rv.name.toString) =>
        val nameString = rv.name.toString
        IO.pure(rv.value.asInstanceOf[Dataset[_]]).flatMap(ds => IO(ds.createOrReplaceTempView(nameString)).as(nameString))
    }.sequence
  }

  private def dropTempViews(views: List[String]): IO[Unit] = views.map(name => IO(spark.catalog.dropTempView(name))).sequence.as(())

  private def outputDataFrame(df: DataFrame): IO[Result] = IO {
    kernelContext.runInterruptible {
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

      Output(
        "text/html",
        s"""<table>
           |  <caption>Showing ${rows.length} of $count rows</caption>
           |  $heading
           |  $body
           |</table>""".stripMargin
      )
    }
  }.handleErrorWith(ErrorResult.applyIO)

  override def runCode(
    cellContext: CellContext,
    code: String
  ): IO[fs2.Stream[IO, Result]] = {
    val compilerRun = new global.Run
    val cell = cellContext.id
    val run = for {
      parseResult <- IO.fromEither(parser.parse(cell, code).fold(Left(_), Right(_), (errs, _) => Left(errs)))
      resultDF    <- registerTempViews(parseResult.tableIdentifiers, cellContext).bracket(_ => IO(spark.sql(code)))(dropTempViews)
      cellResult   = ResultValue(kernelContext, "Out", global.typeOf[DataFrame], resultDF, cell)
    } yield Stream.emit(cellResult) ++ Stream.eval(outputDataFrame(resultDF))

    run.handleErrorWith(ErrorResult.toStream)
  }

  override def completionsAt(
    cellContext: CellContext,
    code: String,
    pos: Int
  ): IO[List[Completion]] = {
    def completeAtPos(statement: SingleStatementContext) = {
      val results = statement.accept(new CompletionVisitor(pos, cellContext.visibleValues))
      results
    }

    for {
      parseResult <- IO.fromEither(parser.parse(cellContext.id, code).toEither)
    } yield completeAtPos(parseResult.statement)
  }

  override def parametersAt(cellContext: CellContext, code: String, pos: Int): IO[Option[Signatures]] =
    IO(None) // TODO: could we generate parameter hints for spark's builtin functions?

  override def init(): IO[Unit] = IO.unit

  override def shutdown(): IO[Unit] = IO.unit

  private val sessionCatalog = SessionStateThief(spark).catalog

  private var databaseNames = sessionCatalog.listDatabases().toArray

  // TODO: could we generate parameter lists and such?
  private lazy val functionNames = sessionCatalog.listFunctions(sessionCatalog.getCurrentDatabase).view.map(_._1.funcName).toArray


  private val databaseTableCache = new mutable.HashMap[String, List[String]]()
  private def tablesOf(db: String): List[String] = databaseTableCache.getOrElseUpdate(db, sessionCatalog.listTables(db).map(_.table).toList)

  private class CompletionVisitor(pos: Int, visibleSymbols: Seq[ResultValue]) extends SqlBaseBaseVisitor[List[Completion]] {
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
  class Factory extends LanguageInterpreter.Factory[IO] {
    override def depManagerFactory: DependencyManagerFactory[IO] = CoursierFetcher.Factory
    override def languageName: String = "SQL"
    override def apply(kernelContext: KernelContext, dependencies: DependencyProvider)(implicit contextShift: ContextShift[IO]): IO[SparkSqlInterpreter] =
      IO.pure(new SparkSqlInterpreter(kernelContext))
  }

  def factory(): Factory = new Factory
}
