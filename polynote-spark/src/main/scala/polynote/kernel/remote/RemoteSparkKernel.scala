package polynote.kernel.remote

import java.net.InetSocketAddress
import java.nio.channels.AsynchronousChannelGroup
import java.util.concurrent.{ExecutorService, Executors}

import cats.effect.{ContextShift, IO}
import polynote.kernel._
import fs2.io.tcp.Socket
import polynote.messages.{ByteVector32, CellID, CellResult, HandleType}
import polynote.runtime.{StreamingDataRepr, TableOp}

import scala.concurrent.ExecutionContext

class RemoteSparkKernel() extends KernelAPI[IO] {
  private val executorService: ExecutorService = Executors.newCachedThreadPool()
  private implicit val contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.fromExecutorService(executorService))
  private implicit val channelGroup: AsynchronousChannelGroup = AsynchronousChannelGroup.withCachedThreadPool(executorService, 1)

  private val address = new InetSocketAddress(java.net.InetAddress.getLocalHost.getHostAddress, 0)
  private val server = Socket.server(address)

  def init: IO[Unit] = ???

  def shutdown(): IO[Unit] = ???

  def startInterpreterFor(id: CellID): IO[fs2.Stream[IO, Result]] = ???

  def runCell(id: CellID): IO[fs2.Stream[IO, Result]] = ???

  def queueCell(id: CellID): IO[IO[fs2.Stream[IO, Result]]] = ???

  def runCells(ids: List[CellID]): IO[fs2.Stream[IO, CellResult]] = ???

  def completionsAt(id: CellID, pos: Int): IO[List[Completion]] = ???

  def parametersAt(id: CellID, pos: Int): IO[Option[Signatures]] = ???

  def currentSymbols(): IO[List[ResultValue]] = ???

  def currentTasks(): IO[List[TaskInfo]] = ???

  def idle(): IO[Boolean] = ???

  def info: IO[Option[KernelInfo]] = ???

  def getHandleData(handleType: HandleType, handle: Int, count: Int): IO[Array[ByteVector32]] = ???

  def modifyStream(handleId: Int, ops: List[TableOp]): IO[Option[StreamingDataRepr]] = ???

  def releaseHandle(handleType: HandleType, handleId: Int): IO[Unit] = ???

  def cancelTasks(): IO[Unit] = ???
}
