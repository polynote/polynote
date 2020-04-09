package polynote.server

import org.scalamock.scalatest.MockFactory
import polynote.env.ops.Enrich
import polynote.kernel.{BaseEnv, CellEnv, GlobalEnv, Kernel}
import polynote.kernel.Kernel.Factory
import polynote.kernel.environment.NotebookUpdates
import polynote.testing.ZIOSpec
import polynote.testing.kernel.{MockEnv, MockKernelEnv}
import zio.{RIO, RManaged, ZIO, ZManaged}

trait MockServerSpec extends MockFactory with ZIOSpec {
  import runtime.unsafeRun
  private val kernel          = mock[Kernel]
  private val kernelFactory   = Factory.const(kernel)

  val testEnv: MockKernelEnv = unsafeRun(MockKernelEnv(kernelFactory))

  implicit class ResolvedEnvIORunOps[A](val self: ZIO[MockEnv.Env, Throwable, A]) {
    def runIO: A = self.provideSomeLayer[Environment](testEnv.baseLayer).runIO()
  }
}
