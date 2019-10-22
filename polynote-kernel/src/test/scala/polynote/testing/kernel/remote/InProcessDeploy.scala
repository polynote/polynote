package polynote.testing.kernel.remote

import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

import polynote.kernel.{BaseEnv, GlobalEnv, Kernel}
import polynote.kernel.environment.CurrentNotebook
import polynote.kernel.remote.{RemoteKernelClient, SocketTransport}
import zio.{Fiber, Ref, RIO, ZIO}
import zio.duration.Duration

class InProcessDeploy(kernelFactory: Kernel.Factory.LocalService, clientRef: Ref[RemoteKernelClient]) extends SocketTransport.Deploy {
  def deployKernel(transport: SocketTransport, serverAddress: InetSocketAddress): RIO[BaseEnv with GlobalEnv with CurrentNotebook, SocketTransport.DeployedProcess] = {
    val connectClient = RemoteKernelClient.tapRunThrowable(
      RemoteKernelClient.Args(
        Some(serverAddress.getHostString),
        Some(serverAddress.getPort),
        Some(kernelFactory)),
      Some(clientRef)).interruptChildren

    connectClient.fork.map(new InProcessDeploy.Process(_))
  }

}

object InProcessDeploy {
  class Process(fiber: Fiber[Throwable, Int]) extends SocketTransport.DeployedProcess {
    def exitStatus: RIO[BaseEnv, Option[Int]] = fiber.poll.flatMap {
      case Some(exit) => ZIO.fromEither(exit.toEither).map(Some(_))
      case None => ZIO.succeed(None)
    }

    def awaitExit(timeout: Long, timeUnit: TimeUnit): RIO[BaseEnv, Option[Int]] =
      fiber.join.timeout(Duration(timeout, timeUnit))

    def kill(): RIO[BaseEnv, Unit] = fiber.interrupt.unit
  }
}
