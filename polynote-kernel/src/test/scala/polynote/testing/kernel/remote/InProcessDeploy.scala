package polynote.testing.kernel.remote

import java.net.{InetAddress, InetSocketAddress}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import polynote.kernel.{BaseEnv, GlobalEnv, Kernel}
import polynote.kernel.environment.CurrentNotebook
import polynote.kernel.logging.Logging
import polynote.kernel.remote.{RemoteKernelClient, SocketTransport}
import zio.{Fiber, RIO, Ref, ZIO}
import zio.duration.Duration

class InProcessDeploy(kernelFactory: Kernel.Factory.LocalService, clientRef: Option[Ref[RemoteKernelClient]]) extends SocketTransport.Deploy {
  def this(kernelFactory: Kernel.Factory.LocalService, clientRef: Ref[RemoteKernelClient]) = this(kernelFactory, Some(clientRef))
  def this(kernelFactory: Kernel.Factory.LocalService) = this(kernelFactory, None)
  def deployKernel(transport: SocketTransport, serverAddress: InetSocketAddress): RIO[BaseEnv with GlobalEnv with CurrentNotebook, SocketTransport.DeployedProcess] = {
    val connectClient = RemoteKernelClient.tapRunThrowable(
      RemoteKernelClient.Args[cats.Id](
        serverAddress.getAddress,
        serverAddress.getPort,
        kernelFactory),
      clientRef)

    connectClient.forkDaemon.map(new InProcessDeploy.Process(_))
  }

}

object InProcessDeploy {
  class Process(fiber: Fiber[Throwable, Int]) extends SocketTransport.DeployedProcess {
    def exitStatus: RIO[BaseEnv, Option[Int]] = fiber.poll.flatMap {
      case Some(exit) => ZIO.fromEither(exit.toEither).map(Some(_))
      case None => ZIO.succeed(None)
    }

    def awaitExit(timeout: Long, timeUnit: TimeUnit): RIO[BaseEnv, Option[Int]] = {
      fiber.join.catchAllCause {
        case cause if cause.interruptedOnly => ZIO.succeed(-1)
        case cause => Logging.error("Process exited with error", cause).as(-2)
      }.timeout(Duration(timeout, timeUnit))
    }

    def kill(): RIO[BaseEnv, Unit] = fiber.interrupt.unit
  }
}
