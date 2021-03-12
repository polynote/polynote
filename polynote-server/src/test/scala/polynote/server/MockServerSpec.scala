package polynote.server

import org.scalamock.scalatest.MockFactory
import polynote.kernel.{BaseEnv, CellEnv, GlobalEnv, Kernel}
import polynote.kernel.Kernel.Factory
import polynote.testing.ZIOSpec
import polynote.testing.kernel.{MockEnv, MockKernelEnv}
import zio.{RIO, ZIO}

trait MockServerSpec extends MockFactory with ZIOSpec {
  import runtime.unsafeRun
  private val kernel          = mock[Kernel]
  private val kernelFactory   = new Factory.Service {
    def apply(): RIO[BaseEnv with GlobalEnv with CellEnv, Kernel] = ZIO.succeed(kernel)
  }

  val testEnv: MockKernelEnv = unsafeRun(MockKernelEnv(kernelFactory))

  implicit class ResolvedEnvIORunOps[A](val self: ZIO[MockEnv.Env, Throwable, A]) {
    def runIO: A = self.provideSomeLayer[Environment](testEnv.baseLayer).runIO()
  }
}
