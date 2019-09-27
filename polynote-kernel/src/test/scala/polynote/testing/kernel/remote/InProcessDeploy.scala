package polynote.testing.kernel.remote

import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

import polynote.kernel.{BaseEnv, GlobalEnv, Kernel}
import polynote.kernel.environment.CurrentNotebook
import polynote.kernel.remote.{RemoteKernelClient, SocketTransport}
import zio.{Fiber, TaskR, ZIO}
import zio.duration.Duration

class InProcessDeploy(kernelFactory: Kernel.Factory.Service) extends SocketTransport.Deploy {
  def deployKernel(transport: SocketTransport, serverAddress: InetSocketAddress): TaskR[BaseEnv with GlobalEnv with CurrentNotebook, SocketTransport.DeployedProcess] = {
    val connectClient = RemoteKernelClient.runThrowable(RemoteKernelClient.Args(
      Some(serverAddress.getHostString),
      Some(serverAddress.getPort),
      Some(kernelFactory))).interruptChildren

    connectClient.fork.map(new InProcessDeploy.Process(_))
  }

}

object InProcessDeploy {
  class Process(fiber: Fiber[Throwable, Int]) extends SocketTransport.DeployedProcess {
    def exitStatus: TaskR[BaseEnv, Option[Int]] = fiber.poll.flatMap {
      case Some(exit) => ZIO.fromEither(exit.toEither).map(Some(_))
      case None => ZIO.succeed(None)
    }

    def awaitExit(timeout: Long, timeUnit: TimeUnit): TaskR[BaseEnv, Option[Int]] =
      fiber.join.timeout(Duration(timeout, timeUnit))

    def kill(): TaskR[BaseEnv, Unit] = fiber.interrupt.unit
  }
}
