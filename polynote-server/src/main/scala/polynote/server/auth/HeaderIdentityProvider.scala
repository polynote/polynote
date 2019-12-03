package polynote.server.auth
import io.circe.{Decoder, Encoder, Json, JsonObject, ObjectEncoder}
import io.circe.generic.extras.semiauto.{deriveDecoder, deriveEncoder}
import org.http4s.util.CaseInsensitiveString
import org.http4s.{Header, Request, Response}
import polynote.kernel.{BaseEnv, environment}
import polynote.kernel.environment.Config
import zio.{RIO, Task, ZIO}
import polynote.config.circeConfig

class HeaderIdentityProvider(val config: HeaderIdentityProvider.Config) extends IdentityProvider.Service {
  override def authRoutes: Option[PartialFunction[Request[Task], RIO[BaseEnv with Config, Response[Task]]]] = None

  override def checkAuth(req: Request[Task]): ZIO[BaseEnv with Config, Response[Task], Option[Identity]] =
    req.headers.get(CaseInsensitiveString(config.header)) match {
      case Some(Header(_, name))         => ZIO.succeed(Some(BasicIdentity(name)))
      case None if config.allowAnonymous => ZIO.succeed(None)
      case None                          => ZIO.fail(Response[Task](status = org.http4s.Status.Forbidden))
    }

  override def checkPermission(
    ident: Option[Identity],
    permission: Permission
  ): ZIO[BaseEnv with Config, Permission.PermissionDenied, Unit] = {
    val matchedUser = ident.map(_.name).getOrElse("*")
    val permissions = config.permissions.getOrElse(matchedUser, Set.empty)
    if (permissions contains permission.permissionType)
      ZIO.unit
    else
      ZIO.fail(Permission.PermissionDenied(permission, s"$matchedUser does not have ${permission.permissionType.encoded} access"))
  }

  override def equals(obj: Any): Boolean = obj match {
    case that: HeaderIdentityProvider => that.config == config
    case _ => false
  }
}

object HeaderIdentityProvider {
  case class Config(
    header: String,
    permissions: Map[String, Set[PermissionType]] = Map("*" -> PermissionType.All),
    allowAnonymous: Boolean = false
  )

  object Config {
    implicit val encoder: ObjectEncoder[Config] = deriveEncoder
    implicit val decoder: Decoder[Config] = deriveDecoder
  }

  class Loader extends ProviderLoader {
    override val providerKey: String = "header"
    override def provider(config: JsonObject): RIO[BaseEnv with environment.Config, HeaderIdentityProvider] =
      ZIO.fromEither(Json.fromJsonObject(config).as[Config]).map(new HeaderIdentityProvider(_))
  }
}