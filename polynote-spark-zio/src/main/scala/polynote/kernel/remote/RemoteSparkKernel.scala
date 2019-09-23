package polynote.kernel.remote

import polynote.kernel.{BaseEnv, CellEnv, GlobalEnv, Kernel}
import zio.{Promise, TaskR}

class RemoteSparkKernel extends Kernel.Factory.Service {
  override def apply(): TaskR[BaseEnv with GlobalEnv with CellEnv, Kernel] =
    RemoteKernel(new SocketTransport(new SocketTransport.DeploySubprocess(DeploySparkSubmit)))
}

object RemoteSparkKernel extends RemoteSparkKernel
