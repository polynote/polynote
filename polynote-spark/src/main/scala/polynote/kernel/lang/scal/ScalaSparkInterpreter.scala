package polynote.kernel.lang.scal

import java.io.File

import cats.effect.IO
import polynote.kernel.lang.LanguageKernel
import polynote.kernel.util.RuntimeSymbolTable

class ScalaSparkInterpreter(symbolTable: RuntimeSymbolTable) extends ScalaInterpreter(symbolTable) {

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

  class Factory extends LanguageKernel.Factory[IO] {
    override def apply(dependencies: List[(String, File)], symbolTable: RuntimeSymbolTable): LanguageKernel[IO] =
      new ScalaSparkInterpreter(symbolTable)
  }

  def factory(): LanguageKernel.Factory[IO] = new Factory

}