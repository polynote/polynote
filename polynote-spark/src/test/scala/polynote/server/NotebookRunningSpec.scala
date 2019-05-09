package polynote.server

import java.nio.file.{Path, Paths}
import java.util.concurrent.Executors

import cats.effect.{ContextShift, ExitCode, IO, Timer}
import fs2.concurrent.Queue
import org.scalatest.{FreeSpec, Matchers}
import polynote.config.PolynoteConfig
import polynote.kernel.{ClearResults, ExecutionInfo, Output, ResultValue}
import polynote.messages.{CellResult, LoadNotebook, Message, ShortString}
import polynote.runtime._
import polynote.server.repository.ipynb.IPythonNotebookRepository

import scala.concurrent.ExecutionContext

// idk, probably should have a better name
class NotebookRunningSpec extends FreeSpec with Matchers  {

  private implicit val executionContext: ExecutionContext = ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
  private implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)
  private implicit val timer: Timer[IO] = IO.timer(executionContext)

  val nbManager = {
    val config = PolynoteConfig()
    val repository       = new IPythonNotebookRepository(Paths.get(getClass.getClassLoader.getResource(s"fixtures/notebooks/").toURI), config, executionContext = executionContext)
    new IONotebookManager(config, repository, SparkServer.kernelFactory)
  }

  "foo" in {
    val path = "test.ipynb"
    val run = for {
      sharedNB <- nbManager.getNotebook(path)
      ref <- sharedNB.open("tester")
      nb <- ref.get
      cellIds = nb.cells.map(_.id)
      res <- ref.runCells(cellIds)
      vec <- res.compile.toVector
    } yield vec

    val res = run.unsafeRunSync()
    val cellResults = res.flatMap {
      case CellResult(_, _, _: ClearResults) => None
      case CellResult(_, _, _: ExecutionInfo) => None
      case CellResult(_, id, result) => Option((id, result))
    }.groupBy(_._1).mapValues(_.map(_._2))

    val expectedCellResults = Map(
      0 -> List(ResultValue("Out","Int",List(StringRepr("1"), DataRepr(IntType,null)),0.toShort,1,null,Some((0,0)))),
      1 -> List(ResultValue("Out","Int",List(StringRepr("2"), DataRepr(IntType,null)),1.toShort,2,null,Some((0,0)))),
      2 -> List(Output("text/plain; rel=stdout","three\n")),
      3 -> List(Output("text/html","<strong>HTML!</strong>")),
      4 -> List(ResultValue("x","Int",List(StringRepr("2"), DataRepr(IntType,null)),4.toShort,2,null,Some((4,4)))),
      5 -> List(ResultValue("y","String",List(StringRepr("hi!"), DataRepr(StringType,null)),5.toShort,"hi!",null,None)),
      6 -> List(
        ResultValue("y","String",List(StringRepr("bye"), DataRepr(StringType,null)),6.toShort,"bye",null,None),
        ResultValue("x","Long",List(StringRepr("3"), DataRepr(LongType,null)),6.toShort,3,null,None),
        ResultValue("z","List[Long]",List(StringRepr("List(1, 2, 3, 4)"), StreamingDataRepr(0,LongType,Some(4))),6.toShort,List(1, 2, 3, 4),null,None)
      ),
      7 -> List(ResultValue("Out","List[Int]",List(StringRepr("List(2, 3, 4, 5)"), StreamingDataRepr(0,IntType,Some(4))),7.toShort,List(2, 3, 4, 5),null,Some((68,68))))
    )

    cellResults.size shouldEqual expectedCellResults.size

    cellResults.foreach {
      case (k, v) =>
        val expectedResults = expectedCellResults(k)
        expectedResults.size shouldEqual v.size

        val filteredResults = v.collect {
          case ResultValue(name, typeName, reprs, sourceCell, value, _, pos) =>

            val filteredReprs = reprs.collect {
              case DataRepr(dataType, buffer) => DataRepr(dataType, null)
              case StreamingDataRepr(_, dataType, size) => StreamingDataRepr(0, dataType, size)
              case other => other
            }

            ResultValue(name, typeName, filteredReprs, sourceCell, value, null, pos)

          case other => other
        }

        filteredResults should contain theSameElementsAs expectedResults
    }

  }

}
