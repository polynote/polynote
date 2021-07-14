package polynote.server.auth

import java.io.{BufferedReader, InputStreamReader}
import java.net.{HttpCookie, URI, InetAddress, InetSocketAddress, URL}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.collection.JavaConverters._

import io.circe.{Decoder, Json, JsonObject, ObjectEncoder}
import io.circe.generic.extras.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.parser.decode
import uzhttp.{HTTPError, Request, Response, Status}, HTTPError.Forbidden
import polynote.kernel.{BaseEnv, environment}
import zio.{RIO, ZIO}
import polynote.config.circeConfig
import polynote.server.Server.Routes

import polynote.kernel.logging.Logging

case class HubIdentityProvider(
  JUPYTERHUB_API_URL: String,
  JPY_API_TOKEN: String,
  JUPYTERHUB_CLIENT_ID: String,
  rdr_url: String,
  permissions: Map[String, Set[PermissionType]] = Map("*" -> PermissionType.All),
  allowAnonymous: Boolean = false
) extends IdentityProvider.Service {

  val client = HttpClient.newHttpClient();

  // I'm not great at making rest calls by hand :/
  def validateLegacyCookie(cookie: HttpCookie): Option[String] = {
    try {
      val name = cookie.getName()
      val value = cookie.getValue()
      val url = s"${JUPYTERHUB_API_URL}/authorizations/cookie/${name}/${value}"
      println(s"Making request to ${url} to validate cookie ${name} with value ${value}")
      val request = HttpRequest.newBuilder()
         .uri(URI.create(url))
         .header("Authorization", s"token $JPY_API_TOKEN")
         .build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      response.statusCode() match {
        case 200 | 201 => Option(response.body())
        case _ => None
      }
    } catch {
      case _ =>
        println("Shit went bom. idk")
        None
    }
  }

  val oauthRoute: PartialFunction[Request, ZIO[BaseEnv, HTTPError, Response]] = {
    case req if req.uri.getPath startsWith rdr_url =>
      val query = req.uri.getQuery
      val split = query.split("=")
      val code = if (split.length > 1) {
        split(1)
      } else {
        ""
      }
      val info = s"Got req ${req} with query ${query} and token ${code}"
      println(info)
      val tokenFetchURL = s"${JUPYTERHUB_API_URL}/oauth2/token"
      println(s"Making request to ${tokenFetchURL} to validate code ${code}")
      val data = HttpRequest.BodyPublishers.ofString(
        s"client_id=${JUPYTERHUB_CLIENT_ID}&client_secret=${JPY_API_TOKEN}&" +
        s"grant_type=authorization_code&code=${code}&redirect_uri=${rdr_url}"
      )
      val tokenRequest = HttpRequest.newBuilder()
         .uri(URI.create(tokenFetchURL))
         .header("Authorization", s"token $JPY_API_TOKEN")
         .POST(data)
         .build()
      val tokenResponse = client.send(tokenRequest, HttpResponse.BodyHandlers.ofString())

      case class OAuthResponse(access_token: String, expires_in: Int, token_type: String, scope: String, refresh_token: String)
      implicit val oauthDecoder: Decoder[OAuthResponse] = deriveDecoder[OAuthResponse]

      val token = decode[OAuthResponse](tokenResponse.body()) match {
        case Right(o) => o.access_token
        case _ => ""
      }

      val url = s"${JUPYTERHUB_API_URL}/authorizations/token/${token}"
      println(s"Making request to ${url} to validate token ${token}")
      val request = HttpRequest.newBuilder()
         .uri(URI.create(url))
         .header("Authorization", s"token $JPY_API_TOKEN")
         .build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      println(s"token validation response is ${response}")
      val userData = response.statusCode() match {
        case 200 | 201 => Option(response.body())
        case _ => None
      }
      println(s"User data ${userData}")
      val cookies = parseCookies(req)
      val jupyterOAuthCookie = cookies.filter(_.getName() == "jupyterhub-session-id").headOption

      case class UserInfo(name: String, admin: Boolean)
      implicit val userInfoDecoder: Decoder[UserInfo] = deriveDecoder[UserInfo]

      userData.foreach { data =>
        println(s"parsing ${data}")
        val user = decode[UserInfo](data) match {
          case Right(o) =>
            println(s"parsed :) ${o}")
            Some(o.name)
          case e =>
            println(s"nope :( ${e}")
            None
        }
        user.foreach { u =>
          println(s"Setting user ${u}")
          users.put(jupyterOAuthCookie.get.getValue(), u)
        }
      }
      ZIO.succeed(Response.plain(info))
  }

  override def authRoutes: Option[Routes] =
    Some(oauthRoute)

  def parseCookies(req: Request) = {
    try {
      val rawCookies = req.headers.getOrElse("cookie", "")
      println(s"rawCookies ${rawCookies}")
      java.net.HttpCookie.parse(rawCookies).asScala.toList
    } catch {
      case _ => Seq()
    }
  }

  val users = scala.collection.mutable.HashMap[String, String]()

  override def checkAuth(req: Request): ZIO[BaseEnv, Response, Option[Identity]] = {
    // Start with trying to validate the JupyterHub 1.3 auth method
    println(s"Parsing cookies from ${req} with headers ${req.headers}")
    val cookies = parseCookies(req)
    println(s"parsed cookies are ${cookies}")
    val cookieNames = cookies.map(_.getName())
    println(s"cookie names are ${cookieNames}")
    val jupyterCookie = cookies.filter(_.getName() == "jupyterhub-hub-login").headOption
    println(s"jupyter cookie is ${jupyterCookie}")
    val username: Option[String] = jupyterCookie.flatMap(validateLegacyCookie)
    println(s"Username is ${username}")
    username match {
      case Some(name) => ZIO.succeed(Some(BasicIdentity(name)))
      case None =>
        // Try and validate with the new 1.4+ Oauth method yay!!!
        val jupyterOAuthCookie = cookies.filter(_.getName() == "jupyterhub-session-id").headOption
        val oauthClientURL =
          s"/hub/api/oauth2/authorize?client_id=${JUPYTERHUB_CLIENT_ID}&response_type=code&redirect_uri=${rdr_url}"
        val oAuthRDR = Response.plain(
          "need oAuth funtimes. BTW I like coffee burgers, do you?",
          Status.Found, List(("Location", oauthClientURL)))
        val user: Option[String] =
          jupyterOAuthCookie.flatMap(cookie => users.get(cookie.getValue()))
        user match {
          case Some(u) =>
            println(s"Found valid session cookie!")
            ZIO.succeed(Some(BasicIdentity(u)))
          case None =>
            println("Doing rdr")
            ZIO.fail(oAuthRDR)
        }
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
      println(s"$matchedUser does not have ${permission.permissionType.encoded} access")
      ZIO.fail(Permission.PermissionDenied(permission, s"$matchedUser does not have ${permission.permissionType.encoded} access"))
  }
}

object HubIdentityProvider {
  implicit val encoder: ObjectEncoder[HubIdentityProvider] = deriveEncoder
  implicit val decoder: Decoder[HubIdentityProvider] = deriveDecoder

  class Loader extends ProviderLoader {
    override val providerKey: String = "hub"
    override def provider(config: JsonObject): RIO[BaseEnv with environment.Config, HubIdentityProvider] =
      ZIO.fromEither(Json.fromJsonObject(config).as[HubIdentityProvider])
  }
}
