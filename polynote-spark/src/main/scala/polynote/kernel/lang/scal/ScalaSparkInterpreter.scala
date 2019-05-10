package polynote.kernel.lang.scal

import java.io.File
import java.util.concurrent.atomic.AtomicInteger

import cats.effect.IO
import polynote.kernel.lang.LanguageInterpreter
import polynote.kernel.util.{CellContext, KernelContext}

class ScalaSparkInterpreter(ctx: KernelContext) extends ScalaInterpreter(ctx) {
  import kernelContext.global

  // need a unique package, in case of a shared spark session
  override lazy val notebookPackageName = s"$$notebook${ScalaSparkInterpreter.nextNotebookId}"

  override protected def mkSource(cellContext: CellContext, code: String, prepend: String = ""): ScalaSource[kernelContext.global.type] = {
    // without this line, inner classes have issues (i.e. no Spark Encoder can be found for case class)
    super.mkSource(cellContext, code, prepend + "org.apache.spark.sql.catalyst.encoders.OuterScopes.addOuterScope(this)\n")
  }

  override def predefCode: Option[String] = Some {
    s"""${super.predefCode.getOrElse("")}
       |import org.apache.spark.sql.SparkSession
       |@transient val spark: SparkSession = if (org.apache.spark.repl.Main.sparkSession != null) {
       |            org.apache.spark.repl.Main.sparkSession
       |          } else {
       |            org.apache.spark.repl.Main.createSparkSession()
       |          }
       |import org.apache.spark.sql.{DataFrame, Dataset}
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
    override def apply(dependencies: List[(String, File)], kernelContext: KernelContext): LanguageInterpreter[IO] =
      new ScalaSparkInterpreter(kernelContext)
  }

  def factory(): LanguageInterpreter.Factory[IO] = new Factory

}