package polynote.kernel.lang.scal

import java.io.File
import java.util.concurrent.atomic.AtomicInteger

import cats.effect.IO
import polynote.kernel.lang.LanguageInterpreter
import polynote.kernel.util.RuntimeSymbolTable

class ScalaSparkInterpreter(st: RuntimeSymbolTable) extends ScalaInterpreter(st) {
  import symbolTable.kernelContext.global

  // need a unique package, in case of a shared spark session
  override protected lazy val notebookPackageName = global.TermName(s"$$notebook${ScalaSparkInterpreter.nextNotebookId}")

  override def predefCode: Option[String] = Some {
    s"""${super.predefCode.getOrElse("")}
       |import org.apache.spark.sql.SparkSession
       |final val spark: SparkSession = SparkSession.builder().getOrCreate()
       |import this.spark.implicits._
       |import org.apache.spark.sql.functions._
     """.stripMargin
  }

}

object ScalaSparkInterpreter {

  private val notebookCounter = new AtomicInteger(0)
  private def nextNotebookId = notebookCounter.getAndIncrement()

  class Factory extends LanguageInterpreter.Factory[IO] {
    override val languageName: String = "Scala"
    override def apply(dependencies: List[(String, File)], symbolTable: RuntimeSymbolTable): LanguageInterpreter[IO] =
      new ScalaSparkInterpreter(symbolTable)
  }

  def factory(): LanguageInterpreter.Factory[IO] = new Factory

}