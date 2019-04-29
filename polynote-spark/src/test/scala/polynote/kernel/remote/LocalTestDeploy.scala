package polynote.kernel.remote
import java.net.InetSocketAddress

import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.{ContextShift, ExitCode, IO, Timer}
import cats.syntax.apply._
import fs2.concurrent.SignallingRef
import polynote.config.PolynoteConfig
import polynote.kernel.util.NotebookContext
import polynote.messages.{NotebookConfig, NotebookUpdate}
import polynote.server.KernelFactory

import scala.concurrent.ExecutionContext

class LocalTestDeploy(kernelFactory: KernelFactory[IO])(
  implicit executionContext: ExecutionContext, contextShift: ContextShift[IO], timer: Timer[IO]
) extends SocketTransport.Deploy {
  def deployKernel(transport: SocketTransport, config: PolynoteConfig, notebookConfig: NotebookConfig, serverAddress: InetSocketAddress)(implicit contextShift: ContextShift[IO]): IO[SocketTransport.DeployedProcess] =
    for {
      transportClient <- transport.connect(serverAddress)
      nbctx           <- SignallingRef[IO, (NotebookContext, Option[NotebookUpdate])]((new NotebookContext(), None))
      remoteClient    = new RemoteSparkKernelClient(transportClient, nbctx, kernelFactory)
    } yield new LocalTestProcess(remoteClient)
}

class LocalTestProcess(client: RemoteSparkKernelClient)(implicit contextShift: ContextShift[IO]) extends SocketTransport.DeployedProcess {
  private val exitCode = Ref.unsafe[IO, Option[ExitCode]](None)
  private val fiber = client.run().flatMap(code => exitCode.set(Some(code))).start.unsafeRunSync()

  override def exitStatus: IO[Option[Int]] = exitCode.get.map(_.map(_.code))

  override def kill(): IO[Unit] = client.shutdown() *> fiber.join
}