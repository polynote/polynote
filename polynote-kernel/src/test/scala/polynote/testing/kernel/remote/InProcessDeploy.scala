package polynote.testing.kernel.remote

import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import polynote.kernel.{BaseEnv, GlobalEnv, Kernel}
import polynote.kernel.environment.CurrentNotebook
import polynote.kernel.logging.Logging
import polynote.kernel.remote.{RemoteKernelClient, SocketTransport}
import zio.{Fiber, RIO, Ref, URIO, ZIO}
import zio.duration.Duration

class InProcessDeploy(kernelFactory: Kernel.Factory.LocalService, clientRef: Ref[RemoteKernelClient]) extends SocketTransport.Deploy {
  def deployKernel(transport: SocketTransport, serverAddress: InetSocketAddress): RIO[BaseEnv with GlobalEnv with CurrentNotebook, SocketTransport.DeployedProcess] = {
    val connectClient = RemoteKernelClient.tapRunThrowable(
      RemoteKernelClient.Args(
        Some(serverAddress.getHostString),
        Some(serverAddress.getPort),
        Some(kernelFactory)),
      Some(clientRef))

    connectClient.forkDaemon.map(new InProcessDeploy.Process(_))
  }

}

object InProcessDeploy {
  class Process(fiber: Fiber[Throwable, Int]) extends SocketTransport.DeployedProcess {
    def exitStatus: URIO[BaseEnv, Option[Int]] = fiber.poll.flatMap {
      case Some(exit) => ZIO.fromEither(exit.toEither).asSome.catchAll(_ => ZIO.some(-1))
      case None => ZIO.none
    }

    def awaitExit(timeout: Long, timeUnit: TimeUnit): RIO[BaseEnv, Option[Int]] = {
      fiber.join.disconnect.timeout(Duration(timeout, timeUnit))
    }

    def kill(): RIO[BaseEnv, Unit] = fiber.interrupt.unit
  }
}
