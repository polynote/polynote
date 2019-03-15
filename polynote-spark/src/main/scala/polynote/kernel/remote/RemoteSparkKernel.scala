package polynote.kernel.remote

import java.net.InetSocketAddress
import java.nio.channels.{AsynchronousChannelGroup, AsynchronousServerSocketChannel, AsynchronousSocketChannel, SocketChannel}
import java.util.concurrent.{ExecutorService, Executors}

import cats.effect.concurrent.Deferred
import cats.effect.{ContextShift, IO}
import polynote.kernel._
import fs2.io.tcp.Socket
import polynote.config.PolynoteConfig
import polynote.kernel.util.Publish
import polynote.messages.{ByteVector32, CellID, CellResult, HandleType, Notebook, NotebookUpdate}
import polynote.runtime.{StreamingDataRepr, TableOp}

import scala.concurrent.ExecutionContext

class RemoteSparkKernel(
  val statusUpdates: Publish[IO, KernelStatusUpdate],
  getNotebook: () => IO[Notebook],
  config: PolynoteConfig
) extends KernelAPI[IO] {
  private val executorService: ExecutorService = Executors.newCachedThreadPool()
  private implicit val contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.fromExecutorService(executorService))
  private implicit val channelGroup: AsynchronousChannelGroup = AsynchronousChannelGroup.withCachedThreadPool(executorService, 1)

  private val server = AsynchronousServerSocketChannel.open(channelGroup).bind(new InetSocketAddress(java.net.InetAddress.getLocalHost.getHostAddress, 0))
  private val address = server.getLocalAddress.asInstanceOf[InetSocketAddress]
  private val clientConnection = Deferred.unsafe[IO, AsynchronousSocketChannel]

  private val jarURL = s"http://${address.getAddress.getHostAddress}:${config.listen.port}/polynote-assembly.jar"
  private val serverHostPort = s"${address.getAddress.getHostAddress}:${address.getPort}"

  //scodec.stream.decode.many[RemoteResponse].decoder
  //Socket.server[IO](address).flatMap(_.use(s => IO.pure(s.reads(8192, None)))).flatten.through(scodec.stream.decode.many[RemoteResponse])

  def init: IO[Unit] = for {
    _ <- statusUpdates.publish1(UpdatedTasks(TaskInfo("kernel", "Starting kernel process", "", TaskStatus.Running) :: Nil))
    notebook      <- getNotebook()
    futureChannel <- IO(server.accept())
    sparkConfig = config.spark ++ notebook.config.flatMap(_.sparkConfig).getOrElse(Map.empty)
    sparkArgs = sparkConfig.flatMap(kv => Seq("--conf", s"${kv._1}=${kv._2}"))
    command = Seq("spark-submit", "--class", classOf[RemoteSparkKernelClient].getName) ++ sparkArgs ++ Seq(jarURL, serverHostPort)
    process <- IO(new ProcessBuilder(command: _*).inheritIO().start())
    channel <- IO(futureChannel.get())

  } yield ()

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

  def updateNotebook(version: Int, update: NotebookUpdate): IO[Unit] = ???
}
