package polynote.kernel.remote
import java.net.InetSocketAddress

import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.{ContextShift, ExitCode, IO, Timer}
import cats.syntax.apply._
import polynote.config.PolynoteConfig
import polynote.messages.NotebookConfig
import polynote.server.KernelFactory

import scala.concurrent.ExecutionContext

class LocalTestDeploy(kernelFactory: KernelFactory[IO])(
  implicit executionContext: ExecutionContext, contextShift: ContextShift[IO], timer: Timer[IO]
) extends SocketTransport.Deploy {
  def deployKernel(transport: SocketTransport, config: PolynoteConfig, notebookConfig: NotebookConfig, serverAddress: InetSocketAddress)(implicit contextShift: ContextShift[IO]): IO[SocketTransport.DeployedProcess] =
    transport.connect(serverAddress).map(new RemoteSparkKernelClient(_, kernelFactory)).map(new LocalTestProcess(_))
}

class LocalTestProcess(client: RemoteSparkKernelClient)(implicit contextShift: ContextShift[IO]) extends SocketTransport.DeployedProcess {
  private val exitCode = Ref.unsafe[IO, Option[ExitCode]](None)
  private val fiber = client.run().flatMap(code => exitCode.set(Some(code))).start.unsafeRunSync()

  override def exitStatus: IO[Option[Int]] = exitCode.get.map(_.map(_.code))

  override def kill(): IO[Unit] = client.shutdown() *> fiber.join
}