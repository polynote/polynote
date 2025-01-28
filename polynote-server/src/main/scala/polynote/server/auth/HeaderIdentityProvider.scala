package polynote.server.auth
import io.circe.{Decoder, Encoder, Json}
import io.circe.generic.extras.semiauto.{deriveConfiguredDecoder, deriveConfiguredEncoder}
import uzhttp.{HTTPError, Request, Response}
import HTTPError.Forbidden
import polynote.kernel.{BaseEnv, environment}
import zio.{RIO, ZIO}
import polynote.config.PluginConfig
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
  implicit val encoder: Encoder.AsObject[HeaderIdentityProvider] = deriveConfiguredEncoder
  implicit val decoder: Decoder[HeaderIdentityProvider] = deriveConfiguredDecoder

  class Loader extends ProviderLoader {
    override val providerKey: String = "header"
    override def provider(config: PluginConfig): RIO[BaseEnv with environment.Config, HeaderIdentityProvider] = {
      ZIO.fromOption {
        for {
          struct         <- config.asStruct
          header         <- struct.get("header").flatMap(_.asValue)
          permissions     = struct.get("permissions").flatMap(_.asStruct).map(readPermissions)
          allowAnon       = struct.get("allow_anonymous").flatMap(_.asValue.map(_ == "true"))
        } yield HeaderIdentityProvider(
          header,
          permissions.getOrElse(Map("*" -> PermissionType.All)),
          allowAnon.getOrElse(false))
      }.mapError(_ => new Exception("Unable to parse header configuration"))
    }

    private def readPermissions(map: Map[String, PluginConfig]): Map[String, Set[PermissionType]] = map.mapValues {
      config => config.asArray.map {
        arr => arr.flatMap(_.asValue.flatMap(str => PermissionType.fromString(str).right.toOption)).toSet
      }.orElse(config.asValue.flatMap {
        case "all" => Some(PermissionType.All)
        case str   => PermissionType.fromString(str).right.toOption.map(Set(_))
      })
    }.collect {
      case (str, Some(perms)) => (str, perms)
    }.toMap
  }
}