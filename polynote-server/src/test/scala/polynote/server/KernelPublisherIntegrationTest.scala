package polynote.server

import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

import org.scalamock.scalatest.MockFactory
import org.scalatest.{FreeSpec, Matchers}
import polynote.config.PolynoteConfig
import polynote.kernel.Kernel.Factory
import polynote.kernel.environment.{Config, Env, NotebookUpdates}
import polynote.kernel.interpreter.Interpreter
import polynote.kernel.remote.SocketTransport.DeploySubprocess
import polynote.kernel.remote.{RemoteKernel, SocketTransport, SocketTransportServer}
import polynote.kernel.remote.SocketTransport.DeploySubprocess.DeployJava
import polynote.kernel.{BaseEnv, CellEnv, GlobalEnv, Kernel, KernelBusyState, KernelError, KernelInfo, LocalKernelFactory}
import polynote.messages.{Notebook, ShortList}
import polynote.testing.{ConfiguredZIOSpec, ExtConfiguredZIOSpec}
import zio.duration.Duration
import zio.{Promise, RIO, Task, ZIO, ZSchedule}

class KernelPublisherIntegrationTest extends FreeSpec with Matchers with ExtConfiguredZIOSpec[Interpreter.Factories] with MockFactory {

  val Environment: Environment = Env.enrichWith[BaseEnv with Config, Interpreter.Factories](baseEnv, new Interpreter.Factories {
    override val interpreterFactories: Map[String, List[Interpreter.Factory]] = Map.empty
  })

  "KernelPublisher" - {

    "gracefully handles death of kernel" in {
      val deploy          = new DeploySubprocess(new DeployJava[LocalKernelFactory])
      val transport       = new SocketTransport(deploy, Some("127.0.0.1"))
      val notebook        = Notebook("/i/am/fake.ipynb", ShortList(Nil), None)
      val kernelFactory   = RemoteKernel.factory(transport)
      val kernelPublisher = KernelPublisher(notebook).runWith(kernelFactory)
      val kernel          = kernelPublisher.kernel.runWith(kernelFactory).asInstanceOf[RemoteKernel[InetSocketAddress]]
      val process         = kernel.transport.asInstanceOf[SocketTransportServer].process

      val collectStatus = kernelPublisher.status.subscribe(5).interruptWhen(kernelPublisher.closed.await.either).compile.toList.fork.runIO()

      process.kill().runIO()
      assert(process.awaitExit(1, TimeUnit.SECONDS).runIO().nonEmpty)

      val kernel2 = kernelPublisher.kernel
        .repeat(ZSchedule.doUntil[Kernel](_ ne kernel))
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
      val stubKernel = stub[Kernel]
      stubKernel.shutdown _ when () returns ZIO.unit
      stubKernel.awaitClosed _ when () returns ZIO.unit
      stubKernel.init _ when () returns ZIO.unit
      stubKernel.info _ when () returns ZIO.succeed(KernelInfo())

      case class FailedToStart() extends Exception("The kernel fails to start. What do you do?")

      val failingKernelFactory: Kernel.Factory = new Kernel.Factory {
        override val kernelFactory: Factory.Service = new Factory.Service {
          private var attempted = 0
          override def apply(): RIO[BaseEnv with GlobalEnv with CellEnv with NotebookUpdates, Kernel] =
            ZIO(attempted).bracket(n => ZIO.effectTotal(attempted = n + 1)) {
              case 0 => ZIO.fail(FailedToStart())
              case n => ZIO.succeed(stubKernel)
            }
        }
      }

      val notebook        = Notebook("/i/am/fake.ipynb", ShortList(Nil), None)
      val kernelPublisher = KernelPublisher(notebook).runWith(failingKernelFactory)

      a [FailedToStart] should be thrownBy {
        kernelPublisher.kernel.runWith(failingKernelFactory)
      }

      val collectStatus = kernelPublisher.status.subscribe(5).interruptWhen(kernelPublisher.closed.await.either).compile.toList.fork.runIO()
      val kernel2 = kernelPublisher.kernel.runWith(failingKernelFactory)
      assert(kernel2 eq stubKernel)
      kernelPublisher.close().runIO()
      val statusUpdates = collectStatus.join.runIO()

      // should have gotten the original startup error in status updates
      statusUpdates should contain (KernelError(FailedToStart()))

    }

  }

}
