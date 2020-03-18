package polynote.server.auth
import io.circe.{Decoder, Json, JsonObject, ObjectEncoder}
import io.circe.generic.extras.semiauto.{deriveDecoder, deriveEncoder}
import uzhttp.{HTTPError, Request, Response}, HTTPError.Forbidden
import polynote.kernel.{BaseEnv, environment}
import zio.{RIO, ZIO}
import polynote.config.circeConfig
import polynote.server.Server.Routes

case class HeaderIdentityProvider(
  header: String,
  permissions: Map[String, Set[PermissionType]] = Map("*" -> PermissionType.All),
  allowAnonymous: Boolean = false
) extends IdentityProvider.Service {
  override def authRoutes: Option[Routes] = None

  override def checkAuth(req: Request): ZIO[BaseEnv, Response, Option[Identity]] =
    req.headers.get(header) match {
      case Some(name)             => ZIO.succeed(Some(BasicIdentity(name)))
      case None if allowAnonymous => ZIO.succeed(None)
      case None                   => ZIO.fail(Response.plain("Anonymous access not allowed", status = Forbidden("Anonymous access not allowed")))
    }

  override def checkPermission(
    ident: Option[Identity],
    permission: Permission
  ): ZIO[BaseEnv, Permission.PermissionDenied, Unit] = {
    val matchedUser = ident.map(_.name).getOrElse("*")
    val anyPermissions = permissions.getOrElse("*", Set.empty)
    val resolvedPermissions = permissions.get(matchedUser).map(_ ++ anyPermissions).getOrElse(anyPermissions)
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