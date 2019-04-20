package polynote.kernel

import java.nio.ByteBuffer

import cats.effect.IO
import cats.syntax.traverse._
import cats.instances.list._
import fs2.Stream
import polynote.config.PolynoteConfig
import polynote.kernel.util.Publish
import polynote.messages.{ByteVector32, CellID, CellResult, HandleType, Notebook, NotebookUpdate, Streaming}
import polynote.runtime._
import DataEncoder.StructDataEncoder
import polynote.server.KernelFactory
import scodec.bits.ByteVector

import scala.reflect.runtime.universe.NoType

object CellIdSyntax {
  class CellIdSyntax(val i: Int) extends AnyVal {
    def cell: CellID = i.toShort
  }
  // implicit class isn't recognized for some reason?
  implicit def intToCellId(i: Int): CellIdSyntax = new CellIdSyntax(i)
}

import CellIdSyntax._

class MockKernelFactory(val kernel: MockKernel) extends KernelFactory[IO] {
  def launchKernel(getNotebook: () => IO[Notebook], statusUpdates: Publish[IO, KernelStatusUpdate], config: PolynoteConfig): IO[KernelAPI[IO]] =
    IO.pure(kernel)
}


// TODO: add more utility to this - maybe record/check API
class MockKernel(@volatile private var notebook: Notebook) extends KernelAPI[IO] {
  def init(): IO[Unit] = IO.unit
  def shutdown(): IO[Unit] = IO.unit

  def startInterpreterFor(id: CellID): IO[Stream[IO, Result]] = IO.pure {
    Stream.emits(MockKernel.results((-1).cell))
  }

  def runCell(id: CellID): IO[Stream[IO, Result]] = IO.pure(Stream.emits(MockKernel.results(id)))

  def queueCell(id: CellID): IO[IO[Stream[IO, Result]]] = IO.pure(runCell(id))

  def runCells(ids: List[CellID]): IO[Stream[IO, CellResult]] =
    ids.map(cell => runCell(cell).map(_.map(CellResult(notebook.path, cell, _)))).sequence.map(Stream.emits).map(_.flatten)

  def completionsAt(id: CellID, pos: Int): IO[List[Completion]] = IO.pure(Nil)

  def parametersAt(id: CellID, pos: Int): IO[Option[Signatures]] = IO.pure(None)

  def currentSymbols(): IO[List[ResultValue]] = IO.pure(Nil)

  def currentTasks(): IO[List[TaskInfo]] = IO.pure(Nil)

  def idle(): IO[Boolean] = IO.pure(true)

  def info: IO[Option[KernelInfo]] = IO.pure(None)

  def getHandleData(handleType: HandleType, handle: Int, count: Int): IO[Array[ByteVector32]] = (handleType, handle) match {
    case (Streaming, MockKernel.twoStreamRepr.handle) => IO.pure(MockKernel.twoStream.toArray)
    case _ => IO.raiseError(new IllegalArgumentException("No handle with that id"))
  }

  def modifyStream(handleId: Int, ops: List[TableOp]): IO[Option[StreamingDataRepr]] = IO.pure(None)

  def releaseHandle(handleType: HandleType, handleId: Int): IO[Unit] = IO.unit

  def cancelTasks(): IO[Unit] = IO.unit

  def updateNotebook(version: Int, update: NotebookUpdate): IO[Unit] = IO {
    this.notebook = update.applyTo(notebook)
  }

  def currentNotebook: Notebook = this.notebook
}

object MockKernel {
  def cellId(int: Int): CellID = int.toShort

  val twoStreamBufs: List[ByteBuffer] = List(
    DataEncoder.writeSized((1, "one")),
    DataEncoder.writeSized((2, "two"))
  )

  val twoStream: List[ByteVector32] = twoStreamBufs.map(buf => ByteVector32(ByteVector(buf)))

  val twoStreamRepr = StreamingDataRepr(
    StructType(List(StructField("foo", IntType), StructField("bar", StringType))),
    Some(twoStream.size),
    twoStreamBufs.iterator
  )

  val results: Map[CellID, List[Result]] = Map(
    (-1).cell -> List(ResultValue("kernel", "Runtime", List(StringRepr("kernel")), (-1).cell, Unit, NoType, None)),
    0.cell -> Nil,
    1.cell -> List(Output("foo/bar", "Cell one"), ResultValue("one", "one", List(StringRepr("one")), 1.cell, Unit, NoType, None)),
    2.cell -> List(
      ResultValue("twoStream", "DataFrame", List(twoStreamRepr), 2.cell, Unit, NoType, None)
    )
  )
}