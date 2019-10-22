package polynote.kernel.interpreter.sql

import org.apache.spark.sql.catalyst.parser.SqlBaseParser.SingleStatementContext
import org.apache.spark.sql.catalyst.parser.{SqlBaseBaseVisitor, SqlBaseParser}
import org.apache.spark.sql.thief.SessionStateThief
import org.apache.spark.sql.{DataFrame, Dataset, SparkSession}
import polynote.kernel.environment.CurrentNotebook
import polynote.kernel.{BaseEnv, Completion, CompletionType, GlobalEnv, InterpreterEnv, ResultValue, ScalaCompiler, Signatures, TaskManager}
import polynote.kernel.interpreter.{Interpreter, State}
import polynote.messages.{ShortString, TinyList}
import polynote.runtime.spark.reprs.SparkReprsOf
import zio.{Task, RIO, ZIO}
import zio.blocking.effectBlocking

import scala.collection.mutable

class SparkSqlInterpreter(compiler: ScalaCompiler) extends Interpreter {
  import compiler.global._
  private val parser = new Parser
  private val spark = SparkSession.builder().getOrCreate()
  private val sessionCatalog = SessionStateThief(spark).catalog
  private val databases = new mutable.TreeSet[String]()
  private val functions = new mutable.TreeSet[String]()
  private val tables = new mutable.HashMap[String, mutable.TreeSet[String]]()

  def run(code: String, state: State): RIO[InterpreterEnv, State] = {
    parser.parse(state.id, code).fold(ZIO.fail, ZIO.succeed, (err, _) => ZIO.fail(err)).flatMap {
      parsed =>
        effectBlocking {
          val idents = parsed.tableIdentifiers.collect {
            case Parser.TableIdentifier(None, name) => name
          }.toSet

          val datasets = state.scope.collect {
            case ResultValue(name, _, _, _, value: Dataset[_], _, _) if idents contains name => name -> value
          }

          datasets.foreach {
            case (name, dataset) => dataset.createOrReplaceTempView(name)
          }

          val result = spark.sql(code)

          State.id(state.id, state.prev, List(new ResultValue("Out", "DataFrame", SparkReprsOf.dataFrame(result).toList, state.id, result, compiler.importType[DataFrame], None)))
        }
    }
  }

  def completionsAt(code: String, pos: Int, state: State): Task[List[Completion]] = {
    def completeAtPos(statement: SingleStatementContext) = {
      val dfs = state.scope.collect {
        case rv if rv.value.isInstanceOf[Dataset[_]] => rv.name: String
      }
      val results = statement.accept(new CompletionVisitor(pos, mutable.TreeSet(dfs: _*) ++ tables.getOrElse(sessionCatalog.getCurrentDatabase, mutable.TreeSet.empty[String])))
      results
    }

    ZIO(parser.parse(state.id, code).right.map(r => completeAtPos(r.statement)).getOrElse(Nil))
  }

  def parametersAt(code: String, pos: Int, state: State): Task[Option[Signatures]] = ZIO.succeed(None)


  private def loadCatalog() = effectBlocking(sessionCatalog.listDatabases()).flatMap {
    dbs => ZIO.sequencePar {
      effectBlocking(sessionCatalog.listFunctions(sessionCatalog.getCurrentDatabase)).map {
        fns => functions ++= fns.map(_._1.funcName)
      } :: dbs.toList.map {
        db => ZIO(databases.add(db)) *> effectBlocking {
          val tableSet = new mutable.TreeSet[String]()
          tables.put(db, tableSet)
          sessionCatalog.listTables(db) foreach {
            table => tableSet.add(table.table)
          }
        }
      }
    }.unit
  }

  def init(state: State): RIO[InterpreterEnv, State] = loadCatalog().fork.as(state)

  def shutdown(): Task[Unit] = ZIO.unit

  private class CompletionVisitor(pos: Int, availableSymbols: mutable.TreeSet[String]) extends SqlBaseBaseVisitor[List[Completion]] {
    override def defaultResult(): List[Completion] = Nil

    override def aggregateResult(aggregate: List[Completion], nextResult: List[Completion]): List[Completion] = aggregate ++ nextResult
    override def visitTableIdentifier(ctx: SqlBaseParser.TableIdentifierContext): List[Completion] = {
      if (pos >= ctx.getStart.getStartIndex && pos <= ctx.getStop.getStopIndex + 1) {
        val db = Option(ctx.db).map(_.getText).filterNot(_.isEmpty)
        val ident = Option(ctx.table).map(_.getText).getOrElse("")
        db match {
          case None =>
            val syms = availableSymbols.range(ident, ident.dropRight(1) + Char.MaxValue).toList.map {
              sym => Completion(sym, Nil, Nil, ShortString(""), CompletionType.Term)
            }
            syms

          case Some(dbName) =>
            val dbTables = tables.getOrElse(dbName, new mutable.TreeSet[String]())
            dbTables.range(ident, ident.dropRight(1) + Char.MaxValue).toList.map {
              table => Completion(table, Nil, Nil, ShortString(""), CompletionType.Term)
            }
        }
      } else super.visitTableIdentifier(ctx)
    }

    override def visitIdentifier(ctx: SqlBaseParser.IdentifierContext): List[Completion] = {
      if (pos > ctx.getStart.getStartIndex && pos <= ctx.getStop.getStopIndex + 1) {
        val part = ctx.getText
        val upper = part.dropRight(1) + Char.MaxValue
        availableSymbols.range(part, upper).toList.map {
          name => Completion(name, Nil, Nil, ShortString(""), CompletionType.Term)
        } ++ functions.range(part, upper).toList.map {
          name => Completion(name, Nil, TinyList(List(TinyList(List.empty))), ShortString(""), CompletionType.Method)
        } ++ databases.range(part, upper).toList.map {
          name => Completion(name, Nil, TinyList(List(TinyList(List.empty))), ShortString(""), CompletionType.Package)
        }
      } else super.visitIdentifier(ctx)
    }
  }
}

object SparkSqlInterpreter {
  def apply(): RIO[ScalaCompiler.Provider, SparkSqlInterpreter] = ZIO.access[ScalaCompiler.Provider](_.scalaCompiler).map {
    compiler => new SparkSqlInterpreter(compiler)
  }

  object Factory extends Interpreter.Factory {
    def languageName: String = "SQL"
    def apply(): RIO[BaseEnv with GlobalEnv with ScalaCompiler.Provider with CurrentNotebook with TaskManager, Interpreter] = SparkSqlInterpreter()
    override val requireSpark: Boolean = true
  }
}