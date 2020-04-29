package polynote.server

import java.net.{HttpURLConnection, InetAddress, InetSocketAddress, URL}

import org.scalamock.scalatest.MockFactory
import org.scalatest.{FreeSpec, Matchers}
import polynote.app.{App, Args, Environment, MainArgs}
import polynote.config._
import polynote.kernel.{BaseEnv, Kernel}
import polynote.kernel.environment.Config
import polynote.kernel.environment.Env.LayerOps
import polynote.kernel.interpreter.Interpreter
import polynote.kernel.logging.Logging
import polynote.server.auth.IdentityProvider
import polynote.server.repository.NotebookRepository
import polynote.server.repository.fs.FileSystems
import polynote.testing.{ConfiguredZIOSpec, ZIOSpec}
import zio.{RIO, Task, ZIO, ZLayer}
import zio.blocking.effectBlocking

class ServerHostTest extends FreeSpec with Matchers with ConfiguredZIOSpec with MockFactory {
  override val config: PolynoteConfig = PolynoteConfig(
    listen = Listen(host = "0.0.0.0", port = 0)
  )

  val configLayer: ZLayer[BaseEnv, Nothing, Config] = ZLayer.succeed(config)

  private def request(uri: String) = effectBlocking {
    val conn = new URL(uri).openConnection().asInstanceOf[HttpURLConnection]
    conn.setConnectTimeout(500)
    conn.connect()
    val responseCode = conn.getResponseCode
    responseCode shouldEqual 200
  }

  "Server" - {

    "listens on all interfaces when given listen=0.0.0.0" ignore {
      val kernel        = mock[Kernel]
      val kernelFactory = Kernel.Factory.const(kernel)
      val server        = new Server

      val serverEnv: ZLayer[BaseEnv, Throwable, server.MainEnv with MainArgs] =
        (configLayer andThen IdentityProvider.layer) ++
          Interpreter.Factories.load ++ ZLayer.succeed(kernelFactory) ++ ZLayer.succeed(Args(watchUI = true)) ++
          (configLayer ++ FileSystems.live >>> NotebookRepository.live)

      val run = server.server("TESTKEY").provideSomeLayer[BaseEnv](serverEnv).use {
        server =>
          for {
            localAddress <- effectBlocking(InetAddress.getLocalHost.getCanonicalHostName)
            _            <- server.awaitUp
            port         <- server.localAddress.map(_.asInstanceOf[InetSocketAddress].getPort)
            _            <- request(s"http://$localAddress:$port/")
            _            <- request(s"http://127.0.0.1:$port/")
            _            <- server.shutdown()
          } yield ()
      }

      run.runIO()
    }

  }

}
