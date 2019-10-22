package polynote.kernel.remote

import polynote.kernel.environment.NotebookUpdates
import polynote.kernel.{BaseEnv, CellEnv, GlobalEnv, Kernel}
import zio.{Promise, RIO}

class RemoteSparkKernel extends Kernel.Factory.Service {
  override def apply(): RIO[BaseEnv with GlobalEnv with CellEnv with NotebookUpdates, Kernel] =
    RemoteKernel(new SocketTransport(new SocketTransport.DeploySubprocess(DeploySparkSubmit)))
}

object RemoteSparkKernel extends RemoteSparkKernel
