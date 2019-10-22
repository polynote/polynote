package polynote.server

import java.util.concurrent.atomic.AtomicInteger

import org.scalamock.scalatest.MockFactory
import org.scalatest.{FeatureSpec, GivenWhenThen}
import polynote.kernel.Kernel.Factory
import polynote.kernel.environment.NotebookUpdates
import polynote.kernel.{BaseEnv, CellEnv, GlobalEnv, Kernel}
import polynote.kernel.util.RefMap
import polynote.testing.ZIOSpec
import polynote.testing.kernel.{MockEnv, MockKernelEnv}
import polynote.testing.repository.MemoryRepository
import zio.{Promise, RIO, ZIO}
import zio.interop.catz._
import zio.interop.catz.implicits._

/**
  * Simulates a server with a couple of clients in order to exercise some scenarios
  */
class ServerIntegrationSpec extends FeatureSpec with ZIOSpec with GivenWhenThen with MockFactory {

  // set up fixture
  private val repository      = new MemoryRepository
  private val notebookManager = unsafeRun(NotebookManager.Service(repository))
  private val kernel          = mock[Kernel]
  private val kernelFactory   = new Factory.Service {
    def apply(): RIO[BaseEnv with GlobalEnv with CellEnv with NotebookUpdates, Kernel] = ZIO.succeed(kernel)
  }

  private val nextSessionId = new AtomicInteger(0)

  class Client() {
    val sessionId: Int = nextSessionId.getAndIncrement()
    val env: MockKernelEnv = unsafeRun(MockKernelEnv(kernelFactory, sessionId))
    val handler = new SessionHandler(notebookManager, unsafeRun(RefMap.empty), unsafeRun(Promise.make), env)
  }

}
