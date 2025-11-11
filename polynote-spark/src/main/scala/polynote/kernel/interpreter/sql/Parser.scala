package polynote.kernel.interpreter.sql

import cats.data.Ior
import org.antlr.v4.runtime._
import org.apache.spark.sql.catalyst.parser._
import polynote.kernel.{CompileErrors, KernelReport, Pos}
import polynote.messages.CellID

import scala.collection.mutable.ListBuffer

class Parser {

  def parse(id: CellID, sql: String): Ior[CompileErrors, Parser.Result] = {
    val lexer = new SqlBaseLexer(new org.antlr.v4.runtime.ANTLRInputStream(sql) {
      override def LA(i: Int): Int = {
        val la = super.LA(i)
        if (la == 0 || la == IntStream.EOF) la
        else Character.toUpperCase(la)
      }
    })

    val tokenStream = new CommonTokenStream(lexer)
    val parser = new SqlBaseParser(tokenStream)

    val errors = ListBuffer[KernelReport]()
    parser.removeErrorListeners()
    parser.addErrorListener(new BaseErrorListener {
      override def syntaxError(recognizer: Recognizer[_, _], offendingSymbol: AnyRef, line: Int, charPositionInLine: Int, msg: String, e: RecognitionException): Unit = {
        errors += report(id, msg, e)
      }
    })

    val tableIdentifiers = ListBuffer[Parser.TableIdentifier]()

    parser.addParseListener(new SqlBaseParserBaseListener {
      override def exitTableIdentifier(ctx: SqlBaseParser.TableIdentifierContext): Unit = {
        val db = Option(ctx.db).map(_.getText).filter(_.nonEmpty)
        val name = Option(ctx.table).map(_.getText).getOrElse("")
        tableIdentifiers += Parser.TableIdentifier(db, name)
      }
    })

    try {
      val statement = parser.singleStatement()
      val result = Parser.Result(statement, tableIdentifiers.toList)
      if (errors.nonEmpty) {
        Ior.both(CompileErrors(errors.toList), result)
      } else {
        Ior.right(result)
      }
    } catch {
      case err: RecognitionException =>
        errors += report(id, Option(err.getMessage).getOrElse(err.toString), err)
        Ior.left(CompileErrors(errors.toList))
    }

  }

  private def report(id: CellID, msg: String, err: RecognitionException): KernelReport = {
    val token = err.getOffendingToken
    val pos = Pos(s"Cell$id", token.getStartIndex, token.getStopIndex, token.getStartIndex)
    KernelReport(pos, msg, KernelReport.Error)
  }
}

object Parser {

  case class TableIdentifier(
    db: Option[String],
    name: String
  )

  case class Result(
    statement: SqlBaseParser.SingleStatementContext,
    tableIdentifiers: List[TableIdentifier]
  )

}
