package polynote.kernel.lang.scal

import java.io.File
import java.util.concurrent.atomic.AtomicInteger

import cats.effect.IO
import polynote.kernel.context.GlobalInfo
import polynote.kernel.lang.LanguageKernel

class ScalaSparkInterpreter(override val globalInfo: GlobalInfo) extends ScalaInterpreter(globalInfo) {
  import globalInfo.global

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

  class Factory() extends LanguageKernel.Factory[IO, GlobalInfo] {
    override val languageName: String = "Scala"
    override def apply(dependencies: List[(String, File)], globalInfo: GlobalInfo): LanguageKernel[IO, GlobalInfo] =
      new ScalaSparkInterpreter(globalInfo)
  }

  def factory(): LanguageKernel.Factory[IO, GlobalInfo] = new Factory()

}