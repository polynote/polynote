package polynote.server

import org.scalamock.scalatest.MockFactory
import polynote.env.ops.Enrich
import polynote.kernel.{BaseEnv, CellEnv, GlobalEnv, Kernel}
import polynote.kernel.Kernel.Factory
import polynote.kernel.environment.NotebookUpdates
import polynote.testing.ZIOSpec
import polynote.testing.kernel.MockKernelEnv
import zio.{RIO, ZIO}

trait MockServerSpec extends MockFactory with ZIOSpec {
  private val kernel          = mock[Kernel]
  private val kernelFactory   = new Factory.Service {
    def apply(): RIO[BaseEnv with GlobalEnv with CellEnv with NotebookUpdates, Kernel] = ZIO.succeed(kernel)
  }

  val testEnv: MockKernelEnv = unsafeRun(MockKernelEnv(kernelFactory))
}
