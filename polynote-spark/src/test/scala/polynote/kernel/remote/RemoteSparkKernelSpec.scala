package polynote.kernel.remote

import java.util.concurrent.{Executors, ThreadFactory}

import cats.effect.concurrent.Ref
import cats.effect.{ContextShift, IO, Timer}
import cats.implicits._
import fs2.concurrent.{Queue, Topic}
import org.scalactic.source.Position
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

  private object testMonitor

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

  private def runTest(fn: => IO[Unit])(implicit pos: Position): Unit = testMonitor.synchronized {
    contextShift.evalOn(executionContext)(fn).unsafeRunSync()
  }

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
    runTest {
      val transport = new LocalTestTransport
      val currentNotebook = Ref[IO].of(initialNotebook).unsafeRunSync()

      transport.expect { _
        .response {
          case Announce(_) => true
        }
        .request {
          case InitialNotebook(_, `initialNotebook`) => true
        }
        .response {
          case UnitResponse(_) => true
        }
      }

      transport.run {
        await =>
          for {
            startKernelF <- RemoteSparkKernel(
              statusUpdates,
              () => currentNotebook.get,
              config,
              transport).start
            remoteClient = new RemoteSparkKernelClient(transport.client, mockKernelFactory)
            runRemote <- remoteClient.run().start
            remoteKernel <- startKernelF.join
            _ <- await
            _ <- remoteKernel.shutdown()
            _ <- runRemote.join
          } yield ()
      }
    }
  }

  "run cell, handle mapping, stream proxy" in {
    runTest {
      val transport = new LocalTestTransport
      for {
        currentNotebook   <- Ref[IO].of(initialNotebook)
        startRemoteKernel <- RemoteSparkKernel(
          statusUpdates,
          () => currentNotebook.get,
          config,
          transport).start

        remoteClient = new RemoteSparkKernelClient(transport.client, mockKernelFactory)
        runRemoteClient <- remoteClient.run().start
        remoteKernel <- startRemoteKernel.join
        _ <- remoteKernel.init

        results <- remoteKernel.runCell(2.toShort).flatMap(_.compile.toList)

        repr = results match {
          case rv @ ResultValue(TinyString("twoStream"), TinyString("DataFrame"), TinyList((repr : StreamingDataRepr) :: Nil), _, _, _) :: Nil => repr
          case other => fail(s"Expected a single ResultValue with a single StreamingDataRepr, got $other")
        }

        _ = repr.handle shouldNot equal (MockKernel.twoStreamRepr.handle)

        buffers <- remoteKernel.getHandleData(Streaming, repr.handle, 2)
        _ = buffers shouldEqual MockKernel.twoStream
        _ <- remoteKernel.shutdown()
        _ <- runRemoteClient.join
      } yield ()
    }

  }

  "integration with shared notebook" - {
    "notebook updates" in {
      runTest {
        val transport = new LocalTestTransport

        val sparkKernelFactory = new SparkRemoteKernelFactory(transport)
        val kernelFactory = mockKernelFactory
        val remoteClient = new RemoteSparkKernelClient(transport.client, kernelFactory)

        for {
          sharedNotebook  <- IOSharedNotebook(path, initialNotebook, sparkKernelFactory, config)
          subscriber      <- sharedNotebook.open("test client")
          ready           <- subscriber.init.start
          runRemoteClient <- remoteClient.run().start
          _               <- ready.join
          update = UpdateCell(path, 0, 0, 0.toShort, ContentEdits(Insert(2, "insert")))
          _               <- subscriber.update(update)
          _ = kernelFactory.kernel.currentNotebook shouldEqual update.applyTo(initialNotebook)
          _ <- sharedNotebook.shutdown()
          _ <- runRemoteClient.join
        } yield ()
      }
    }
  }

  "actual (local) networking" - {
    "run cell, handle mapping, stream proxy" in {
      runTest {
        val transport = new SocketTransport(new LocalTestDeploy(mockKernelFactory))
        for {
          currentNotebook <- Ref[IO].of(initialNotebook)
          kernel <- RemoteSparkKernel(statusUpdates, currentNotebook.get _, config, transport)

          results <- kernel.runCell(2.toShort).flatMap(_.compile.toList)

          repr = results match {
            case rv @ ResultValue(TinyString("twoStream"), TinyString("DataFrame"), TinyList((repr : StreamingDataRepr) :: Nil), _, _, _) :: Nil => repr
            case other => fail(s"Expected a single ResultValue with a single StreamingDataRepr, got $other")
          }

          _ = repr.handle shouldNot equal (MockKernel.twoStreamRepr.handle)

          buffers <- kernel.getHandleData(Streaming, repr.handle, 2)
          _ = buffers shouldEqual MockKernel.twoStream
          _ <- kernel.shutdown()
        } yield ()
      }
    }
  }

}
