package polynote.server

import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

import cats.instances.list._
import cats.syntax.traverse._
import fs2.concurrent.Topic
import org.scalamock.scalatest.MockFactory
import org.scalatest.{FreeSpec, Matchers}
import polynote.config.PolynoteConfig
import polynote.kernel.Kernel.Factory
import polynote.kernel.environment.{Config, Env, NotebookUpdates, PublishResult}
import polynote.kernel.interpreter.Interpreter
import polynote.kernel.remote.SocketTransport.DeploySubprocess
import polynote.kernel.remote.{RemoteKernel, SocketTransport, SocketTransportServer}
import polynote.kernel.remote.SocketTransport.DeploySubprocess.DeployJava
import polynote.kernel.{BaseEnv, CellEnv, GlobalEnv, Kernel, KernelBusyState, KernelError, KernelInfo, KernelStatusUpdate, LocalKernel, LocalKernelFactory, Output}
import polynote.messages.{CellID, Message, Notebook, NotebookCell, ShortList}
import polynote.testing.ExtConfiguredZIOSpec
import polynote.testing.kernel.MockNotebookRef
import zio.duration.Duration
import zio.{Promise, RIO, Schedule, Tagged, Task, ZIO, ZLayer}

class KernelPublisherIntegrationTest extends FreeSpec with Matchers with ExtConfiguredZIOSpec[Interpreter.Factories] with MockFactory {
  val tagged: Tagged[Interpreter.Factories] = implicitly

  override lazy val configuredEnvLayer: ZLayer[zio.ZEnv with Config, Nothing, Interpreter.Factories] = ZLayer.succeed(Map.empty)

  private def mkStubKernel = {
    val stubKernel = stub[Kernel]
    stubKernel.shutdown _ when () returns ZIO.unit
    stubKernel.awaitClosed _ when () returns ZIO.unit
    stubKernel.init _ when () returns ZIO.unit
    stubKernel.info _ when () returns ZIO.succeed(KernelInfo())
    stubKernel
  }

  private val bq = mock[Topic[Task, Option[Message]]]

  "KernelPublisher" - {

    "collapses carriage returns in saved notebook" in {
      val kernel          = mkStubKernel
      val kernelFactory   = Kernel.Factory.const(kernel)
      kernel.queueCell _ when (CellID(0)) returns ZIO.environment[CellEnv].map {
        env =>
          ZIO.foreach(0 until 100) {
            i => PublishResult(Output("text/plain; rel=stdout", s"$i\r"))
          }.flatMap {
            _ => PublishResult(Output("text/plain; rel=stdout", "end\n"))
          }.provideLayer(ZLayer.succeedMany(env))
      }
      val notebook        = Notebook("/i/am/fake.ipynb", ShortList(List(NotebookCell(CellID(0), "scala", ""))), None)
      val ref             = MockNotebookRef(notebook).runIO()
      val kernelPublisher = KernelPublisher(ref, bq).runWith(kernelFactory)
      kernelPublisher.queueCell(CellID(0)).flatten.runWith(kernelFactory)
      kernelPublisher.latestVersion.runIO()._2.cells.head.results should contain theSameElementsAs Seq(
        Output("text/plain; rel=stdout", "end\n")
      )
    }

    "gracefully handles death of kernel" in {
      val deploy          = new DeploySubprocess(new DeployJava[LocalKernelFactory])
      val transport       = new SocketTransport(deploy)
      val notebook        = Notebook("/i/am/fake.ipynb", ShortList(Nil), None)
      val ref             = MockNotebookRef(notebook).runIO()
      val kernelFactory   = RemoteKernel.factory(transport)
      val kernelPublisher = KernelPublisher(ref, bq).runWith(kernelFactory)
      val kernel          = kernelPublisher.kernel.runWith(kernelFactory).asInstanceOf[RemoteKernel[InetSocketAddress]]
      val process         = kernel.transport.asInstanceOf[SocketTransportServer].process

      val collectStatus = kernelPublisher.status.subscribe(5).interruptWhen(kernelPublisher.closed.await.either).compile.toList.forkDaemon.runIO()

      process.kill().runIO()
      assert(process.awaitExit(1, TimeUnit.SECONDS).runIO().nonEmpty)

      val kernel2 = kernelPublisher.kernel
        .repeat(Schedule.doUntil[Kernel](_ ne kernel))
        .timeout(Duration(20, TimeUnit.SECONDS))
        .someOrFail(new Exception("Kernel should have changed; didn't change after 5 seconds"))
        .runWith(kernelFactory)

      assert(!(kernel2 eq kernel), "Kernel should have changed")
      kernelPublisher.close().runIO()
      val statusUpdates = collectStatus.join.runIO()

      // should have gotten a notification that the kernel became dead
      statusUpdates should contain (KernelBusyState(busy = false, alive = false))

      // should have gotten some kernel error
      statusUpdates.collect {
        case KernelError(err) => err
      }.size shouldEqual 1

    }

    "gracefully handles startup failure of kernel" in {
      val stubKernel = mkStubKernel

      case class FailedToStart() extends Exception("The kernel fails to start. What do you do?")

      val failingKernelFactory: Factory.Service = new Factory.Service {
        private var attempted = 0
        override def apply(): RIO[BaseEnv with GlobalEnv with CellEnv with NotebookUpdates, Kernel] =
          ZIO(attempted).bracket(n => ZIO.effectTotal(attempted = n + 1)) {
            case 0 => ZIO.fail(FailedToStart())
            case n => ZIO.succeed(stubKernel)
          }
      }

      val notebook        = Notebook("/i/am/fake.ipynb", ShortList(Nil), None)
      val ref             = MockNotebookRef(notebook).runIO()
      val kernelPublisher = KernelPublisher(ref, bq).runWith(failingKernelFactory)
      val stopStatus = Promise.make[Throwable, Unit].runIO()
      val collectStatus = kernelPublisher.status.subscribe(5).interruptWhen(stopStatus.await.either).compile.toList.forkDaemon.runIO()

      a [FailedToStart] should be thrownBy {
        kernelPublisher.kernel.runWith(failingKernelFactory)
      }

      val kernel2 = kernelPublisher.kernel.runWith(failingKernelFactory)
      assert(kernel2 eq stubKernel)
      kernelPublisher.close().runIO()
      stopStatus.succeed(()).runIO()
      val statusUpdates = collectStatus.join.runIO()

      // should have gotten the original startup error in status updates
      statusUpdates should contain (KernelError(FailedToStart()))

    }

  }

}
