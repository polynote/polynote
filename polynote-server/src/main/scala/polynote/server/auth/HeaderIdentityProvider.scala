package polynote.server.auth
import io.circe.{Decoder, Encoder, Json, JsonObject, ObjectEncoder}
import io.circe.generic.extras.semiauto.{deriveDecoder, deriveEncoder}
import org.http4s.util.CaseInsensitiveString
import org.http4s.{Header, Request, Response}
import polynote.kernel.{BaseEnv, environment}
import polynote.kernel.environment.Config
import zio.{RIO, Task, ZIO}
import polynote.config.circeConfig

case class HeaderIdentityProvider(
  header: String,
  permissions: Map[String, Set[PermissionType]] = Map("*" -> PermissionType.All),
  allowAnonymous: Boolean = false
) extends IdentityProvider.Service {
  override def authRoutes: Option[PartialFunction[Request[Task], RIO[BaseEnv, Response[Task]]]] = None

  override def checkAuth(req: Request[Task]): ZIO[BaseEnv, Response[Task], Option[Identity]] =
    req.headers.get(CaseInsensitiveString(header)) match {
      case Some(Header(_, name))         => ZIO.succeed(Some(BasicIdentity(name)))
      case None if allowAnonymous => ZIO.succeed(None)
      case None                          => ZIO.fail(Response[Task](status = org.http4s.Status.Forbidden))
    }

  override def checkPermission(
    ident: Option[Identity],
    permission: Permission
  ): ZIO[BaseEnv, Permission.PermissionDenied, Unit] = {
    val matchedUser = ident.map(_.name).getOrElse("*")
    val resolvedPermissions = permissions.getOrElse(matchedUser, Set.empty)
    if (resolvedPermissions contains permission.permissionType)
      ZIO.unit
    else
      ZIO.fail(Permission.PermissionDenied(permission, s"$matchedUser does not have ${permission.permissionType.encoded} access"))
  }
}

object HeaderIdentityProvider {
  implicit val encoder: ObjectEncoder[HeaderIdentityProvider] = deriveEncoder
  implicit val decoder: Decoder[HeaderIdentityProvider] = deriveDecoder

  class Loader extends ProviderLoader {
    override val providerKey: String = "header"
    override def provider(config: JsonObject): RIO[BaseEnv with environment.Config, HeaderIdentityProvider] =
      ZIO.fromEither(Json.fromJsonObject(config).as[HeaderIdentityProvider])
  }
}