package polynote.server.auth

import cats.syntax.traverse._
import cats.instances.option._
import io.circe.{Json, JsonObject}
import io.circe.syntax.EncoderOps
import org.scalatest.{FreeSpec, Matchers}
import polynote.config.{AuthProvider, PolynoteConfig, Security}
import polynote.kernel.environment.{Config, Env}
import polynote.testing.ZIOSpec
import polynote.messages.CellID
import uzhttp.{Request, Response, Status, HTTPError}
import zio.{RIO, Task, ZIO, ZLayer}
import zio.interop.catz._

class HubIdentityProviderSpec extends FreeSpec with Matchers with ZIOSpec {
  private def createProvider(allowAnonymous: Boolean): HubIdentityProvider = HubIdentityProvider(
    "http://localhost/bah",
    "CHEESEBURGER",
    Map(
      "bob" -> Set(PermissionType.ReadNotebook, PermissionType.ModifyNotebook),
      "alice" -> PermissionType.All,
      "*" -> Set(PermissionType.ReadNotebook))
  )

  private def authConfig(allowAnonymous: Boolean) = PolynoteConfig(security = Security(auth = Some(AuthProvider(
    provider = "header",
    config = createProvider(allowAnonymous).asJsonObject))))

  def loadFrom(config: PolynoteConfig): Option[IdentityProvider.Service] = config.security.auth.map(IdentityProvider.find).sequence.runWithConfig(config)

  "HubIdentityProvider" - {

    "loads from config YAML" in {
      val yaml =
        s"""security:
           |  auth:
           |    provider: hub
           |    config:
           |      JUPYTERHUB_API_URL: localhost
           |      JPY_API_TOKEN: CHEESEBURGER
           |      permissions:
           |        bob:
           |             - read
           |             - modify
           |        alice: all
           |        "*"  : read
           |      allow_anonymous: false
           |""".stripMargin

      val parsed = PolynoteConfig.parse(yaml).fold(throw _, identity)
      loadFrom(parsed) shouldEqual loadFrom(authConfig(false))
    }
}
