package polynote.kernel.lang.scal

import java.io.File
import java.util.concurrent.atomic.AtomicInteger

import cats.effect.{ContextShift, IO}
import cats.effect.concurrent.Semaphore
import polynote.kernel.dependency.{ClassLoaderDependencyProvider, CoursierFetcher, DependencyManagerFactory, DependencyProvider}
import polynote.kernel.lang.LanguageInterpreter
import polynote.kernel.util.{CellContext, KernelContext}

class ScalaSparkInterpreter(ctx: KernelContext, interpreterLock: Semaphore[IO]) extends ScalaInterpreter(ctx, interpreterLock) {
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
       |import spark.implicits._
       |import org.apache.spark.sql.functions._
     """.stripMargin
  }

}

object ScalaSparkInterpreter {

  private val notebookCounter = new AtomicInteger(0)
  private def nextNotebookId = notebookCounter.getAndIncrement()

  class Factory extends ScalaInterpreter.Factory {
    override def apply(kernelContext: KernelContext, dependencies: DependencyProvider)(implicit contextShift: ContextShift[IO]): IO[ScalaInterpreter] = {
      for {
        interpreterLock <- Semaphore[IO](1)
      } yield new ScalaSparkInterpreter(kernelContext, interpreterLock)
    }
  }

  def factory(): LanguageInterpreter.Factory[IO] = new Factory

}