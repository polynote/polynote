package polynote.server.auth

import cats.syntax.traverse._
import cats.instances.option._
import io.circe.{Json, JsonObject}
import io.circe.syntax.EncoderOps
import org.http4s.util.CaseInsensitiveString
import org.http4s.{Header, Headers, Request, Response, Status}
import org.scalatest.{FreeSpec, Matchers}
import polynote.config.{AuthProvider, PolynoteConfig, Security}
import polynote.kernel.environment.{Config, Env}
import polynote.testing.ZIOSpec
import polynote.messages.CellID
import zio.{RIO, Task, ZIO}
import zio.interop.catz._

class HeaderIdentityProviderSpec extends FreeSpec with Matchers with ZIOSpec {

  private def createProvider(allowAnonymous: Boolean) = HeaderIdentityProvider(
    "X-User-Name",
    Map(
      "bob" -> Set(PermissionType.ReadNotebook, PermissionType.ModifyNotebook),
      "alice" -> PermissionType.All,
      "*" -> Set(PermissionType.ReadNotebook)),
    allowAnonymous
  )

  private def authConfig(allowAnonymous: Boolean) = PolynoteConfig(security = Security(auth = Some(AuthProvider(
    provider = "header",
    config = createProvider(allowAnonymous).asJsonObject))))

  def loadFrom(config: PolynoteConfig): Option[IdentityProvider.Service] = config.security.auth.map(IdentityProvider.find).sequence.runIO(config)

  "HeaderIdentityProvider" - {

    "loads from config YAML" in {
      val yaml =
        s"""security:
           |  auth:
           |    provider: header
           |    config:
           |      header: X-User-Name
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

    "when specified header is missing" - {
      val ok = ZIO.effectTotal(Response[Task](Status.Ok))

      "fails when allowMissing = false" in {
        val config = authConfig(false)
        val authorize = IdentityProvider.authorize[Environment with Config]
          .provideSomeM(Env.enrichM[Environment with Config](IdentityProvider.load))
          .runIO(config)
        authorize(Request(), ok).runIO(config).status shouldEqual Status.Forbidden
      }

      "succeeds when allowMissing = true" in {
        val config = authConfig(true)
        val authorize = IdentityProvider.authorize[Environment with Config]
          .provideSomeM(Env.enrichM[Environment with Config](IdentityProvider.load))
          .runIO(config)

        authorize(Request(), ok).runIO(config).status shouldEqual Status.Ok
      }
    }

    "provides a user identity" - {
      val config = authConfig(true)
      val authorize = IdentityProvider.authorize[Environment with Config]
        .provideSomeM(Env.enrichM[Environment with Config](IdentityProvider.load))
        .runIO(config)

      val response = ZIO.access[UserIdentity](_.userIdentity).map {
        identity =>
          Response[Task](headers = Headers(identity.map(id => Header("FoundIdentity", id.name)).toList))
      }

      def check(name: Option[String]) =
        authorize(Request(headers = Headers(name.map(Header("X-User-Name", _)).toList)), response).runIO(config)
          .headers.get(CaseInsensitiveString("FoundIdentity"))
          .map(_.value)

      "non-empty when header is present" in {
        check(Some("bob")) shouldEqual Some("bob")
        check(Some("alice")) shouldEqual Some("alice")
        check(Some("unknownperson")) shouldEqual Some("unknownperson")
      }

      "empty when header is absent" in {
        check(None) shouldEqual None
      }
    }

    "checks permissions" - {
      import Permission._
      val provider = createProvider(true)
      def check(name: Option[String], permission: Permission): Unit =
        IdentityProvider.checkPermission(permission)
          .provideSomeM(Env.enrich[Environment with Config with IdentityProvider](UserIdentity.of(name.map(BasicIdentity.apply))))
          .provideSomeM(Env.enrich[Environment with Config](IdentityProvider.of(provider)))
          .runIO(PolynoteConfig())

      def checkFail(name: Option[String], permission: Permission): Unit = a [PermissionDenied] shouldBe thrownBy {
        check(name, permission)
      }

      def checkAll(name: Option[String], permissions: Permission*): Unit = permissions.foreach(check(name, _))
      def checkAllFail(name: Option[String], permissions: Permission*): Unit = permissions.foreach(checkFail(name, _))

      val read = ReadNotebook("/path")
      val modify = ModifyNotebook("/path")
      val execute = ExecuteCell("/path", CellID(1))
      val delete = DeleteNotebook("/path")
      val create = CreateNotebook("/path")

      "for known users" in {
        checkAll(Some("bob"), read, modify)
        checkAllFail(Some("bob"), execute, delete, create)
        checkAll(Some("alice"), read, modify, execute, delete, create)
      }

      "for unknown users" in {
        check(None, read)
        checkAllFail(None, modify, execute, delete, create)
      }

    }

  }

}
