package polynote.kernel.remote

import java.util.concurrent.{Executors, ThreadFactory}

import cats.effect.concurrent.Ref
import cats.effect.{ContextShift, IO, Timer}
import cats.implicits._
import fs2.concurrent.{Queue, Topic}
import org.scalatest.{FreeSpec, Matchers}
import polynote.config.PolynoteConfig
import polynote.kernel._
import polynote.messages._
import polynote.kernel.util.Publish._
import polynote.runtime.StreamingDataRepr
import polynote.server.{IOSharedNotebook, SparkRemoteKernelFactory}

import scala.concurrent.ExecutionContext

// TODO: We should have a shared test suite for KernelAPI implementations that can run against PolyKernel, SparkPolyKernel, and RemoteSparkKernel
//       But first we have to replace RuntimeSymbolTable with CellContext.
//       This will just be remote-specific stuff
class RemoteSparkKernelSpec extends FreeSpec with Matchers {

  private implicit val executionContext: ExecutionContext = ExecutionContext.fromExecutorService(
    Executors.newCachedThreadPool(new ThreadFactory {
      def newThread(r: Runnable): Thread = {
        val thread = new Thread(r)
        thread.setDaemon(true)
        thread.setName("Test runner thread")
        thread
      }
    })
  )

  private implicit val contextShift: ContextShift[IO] = IO.contextShift(executionContext)
  private implicit val timer: Timer[IO] = IO.timer(executionContext)

  private val path = ShortString("testnotebook")

  private val outputMessages = Queue.unbounded[IO, Message].unsafeRunSync()
  private val statusUpdates = outputMessages.contramap[KernelStatusUpdate](KernelStatus(path, _))

  private val initialNotebook = Notebook(
    path,
    ShortList(List(
      NotebookCell(0.toShort, "scala", "val foo = 22")
    )),
    None
  )

  private val config = PolynoteConfig()
  private def mockKernel = new MockKernel(initialNotebook)
  private def mockKernelFactory = new MockKernelFactory(mockKernel)

  "protocol handshake" in {
    val transport = new LocalTestTransport
    val currentNotebook = Ref[IO].of(initialNotebook).unsafeRunSync()

    transport.expect { _
        .response { case Announce(_) => true }
        .request  { case InitialNotebook(_, `initialNotebook`) => true }
        .response { case UnitResponse(_) => true }
    }

    transport.run { await =>
      for {
        startKernelF <- RemoteSparkKernel(
                          statusUpdates,
                          () => currentNotebook.get,
                          config,
                          transport).start
        remoteClient  = new RemoteSparkKernelClient(transport.client, mockKernelFactory)
        runRemote    <- remoteClient.run().start
        remoteKernel <- startKernelF.join
        _            <- await
        _            <- remoteKernel.shutdown()
        _            <- runRemote.join
      } yield ()
    }.unsafeRunSync()
  }

  "run cell, handle mapping, stream proxy" in {
    val transport = new LocalTestTransport
    val currentNotebook = Ref[IO].of(initialNotebook).unsafeRunSync()

    val startRemoteKernel = RemoteSparkKernel(
      statusUpdates,
      () => currentNotebook.get,
      config,
      transport).start.unsafeRunSync()

    val remoteClient = new RemoteSparkKernelClient(transport.client, mockKernelFactory)
    val runRemoteClient = remoteClient.run().start.unsafeRunSync()
    val remoteKernel = startRemoteKernel.join.unsafeRunSync()
    remoteKernel.init.unsafeRunSync()

    val results = remoteKernel.runCell(2.toShort).unsafeRunSync().compile.toList.unsafeRunSync()
    val repr = results match {
      case rv @ ResultValue(TinyString("twoStream"), TinyString("DataFrame"), TinyList((repr : StreamingDataRepr) :: Nil), _, _, _) :: Nil => repr
      case other => fail(s"Expected a single ResultValue with a single StreamingDataRepr, got $other")
    }

    repr.handle shouldNot equal (MockKernel.twoStreamRepr.handle)

    val buffers = remoteKernel.getHandleData(Streaming, repr.handle, 2).unsafeRunSync().toList

    buffers shouldEqual MockKernel.twoStream

    remoteKernel.shutdown().unsafeRunSync()
    runRemoteClient.join.unsafeRunSync()

  }

  "integration with shared notebook" - {
    "notebook updates" in {
      val transport = new LocalTestTransport

      val sparkKernelFactory = new SparkRemoteKernelFactory(transport)
      val kernelFactory = mockKernelFactory

      val remoteClient = new RemoteSparkKernelClient(transport.client, kernelFactory)

      val sharedNotebook = IOSharedNotebook(path, initialNotebook, sparkKernelFactory, config).unsafeRunSync()
      val subscriber = sharedNotebook.open("test client").unsafeRunSync()
      val ready = subscriber.init.start.unsafeRunSync()
      val runRemoteClient = remoteClient.run().start.unsafeRunSync()
      ready.join.unsafeRunSync()

      val update = UpdateCell(path, 0, 0, 0.toShort, ContentEdits(Insert(2, "insert")))
      subscriber.update(update).unsafeRunSync()

      kernelFactory.kernel.currentNotebook shouldEqual update.applyTo(initialNotebook)

      sharedNotebook.shutdown().unsafeRunSync()
      runRemoteClient.join.unsafeRunSync()
    }
  }

  "actual (local) networking" - {
    "run cell, handle mapping, stream proxy" in {
      val transport = new SocketTransport(new LocalTestDeploy(mockKernelFactory))
      val currentNotebook = Ref[IO].of(initialNotebook).unsafeRunSync()
      val kernel = RemoteSparkKernel(statusUpdates, currentNotebook.get _, config, transport).unsafeRunSync()
      val results = kernel.runCell(2.toShort).unsafeRunSync().compile.toList.unsafeRunSync()
      val repr = results match {
        case rv @ ResultValue(TinyString("twoStream"), TinyString("DataFrame"), TinyList((repr : StreamingDataRepr) :: Nil), _, _, _) :: Nil => repr
        case other => fail(s"Expected a single ResultValue with a single StreamingDataRepr, got $other")
      }

      repr.handle shouldNot equal (MockKernel.twoStreamRepr.handle)

      val buffers = kernel.getHandleData(Streaming, repr.handle, 2).unsafeRunSync().toList

      buffers shouldEqual MockKernel.twoStream

      kernel.shutdown().unsafeRunSync()
    }
  }

}
