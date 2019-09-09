package polynote.server

import polynote.kernel.{Kernel, LocalSparkKernel}

object ZIOSparkServer extends ZIOServer {
  protected val kernelFactory: Kernel.Factory.Service = LocalSparkKernel
}
