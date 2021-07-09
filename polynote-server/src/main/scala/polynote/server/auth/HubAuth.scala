
package polynote.server.auth

import java.io.{BufferedReader, InputStreamReader}
import java.net.{HttpURLConnection, InetAddress, InetSocketAddress, URL}
import scala.collection.JavaConverters._
    
import io.circe.{Decoder, Json, JsonObject, ObjectEncoder}
import io.circe.generic.extras.semiauto.{deriveDecoder, deriveEncoder}
import uzhttp.{HTTPError, Request, Response}, HTTPError.Forbidden
import polynote.kernel.{BaseEnv, environment}
import zio.{RIO, ZIO}
import polynote.config.circeConfig
import polynote.server.Server.Routes

case class HubIdentityProvider(
  JUPYTERHUB_API_URL: String,
  JPY_API_TOKEN: String,
  permissions: Map[String, Set[PermissionType]] = Map("*" -> PermissionType.All),
  allowAnonymous: Boolean = false
) extends IdentityProvider.Service {
  
  def validateCookie(cookie: String): Option[String] = {
    val url = s"${JUPYTERHUB_API_URL}/user"
    val conn = new URL(url).openConnection().asInstanceOf[HttpURLConnection]
    conn.setConnectTimeout(500)
    conn.connect()
    val responseCode = conn.getResponseCode
    responseCode match {
      case 200 | 201 =>
	val responseStreamReader = new BufferedReader(new InputStreamReader(conn.getInputStream()))
        Option(responseStreamReader.readLine())
      case _ => None
    }
  }
    
  override def authRoutes: Option[Routes] = None
  
  override def checkAuth(req: Request): ZIO[BaseEnv, Response, Option[Identity]] = {
    val cookies = java.net.HttpCookie.parse(req.headers.getOrElse("cookies", ""))
    val jupyterCookie = cookies.asScala.filter(_.getName() == "jupyterhub-hub-login").map(_.getValue()).headOption
    val username: Option[String] = jupyterCookie.flatMap(validateCookie)
    username match {
      case Some(name) => ZIO.succeed(Some(BasicIdentity(name)))
      case None => ZIO.fail(Response.plain("Anonymous access not allowed", status = Forbidden("Anonymous access not allowed")))
    }
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

object HubIdentityProvider {
  implicit val encoder: ObjectEncoder[HeaderIdentityProvider] = deriveEncoder
  implicit val decoder: Decoder[HeaderIdentityProvider] = deriveDecoder

  class Loader extends ProviderLoader {
    override val providerKey: String = "header"
    override def provider(config: JsonObject): RIO[BaseEnv with environment.Config, HeaderIdentityProvider] =
      ZIO.fromEither(Json.fromJsonObject(config).as[HeaderIdentityProvider])
  }
}
