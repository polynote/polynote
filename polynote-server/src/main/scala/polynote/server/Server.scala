package polynote.server

import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.{Path, Paths}
import java.util.UUID

import fs2.concurrent.Topic
import polynote.buildinfo.BuildInfo
import polynote.app.{Args, Environment, MainArgs}
import polynote.config.PolynoteConfig
import polynote.kernel.environment.{Config, Env}
import Env.LayerOps
import polynote.kernel.logging.Logging
import polynote.kernel.{BaseEnv, GlobalEnv, Kernel}
import polynote.messages.Message
import polynote.server.auth.IdentityProvider
import uzhttp.server.ServerLogger
import uzhttp.{HTTPError, Request, Response}
import HTTPError.{Forbidden, InternalServerError, NotFound}
import polynote.kernel.interpreter.Interpreter
import polynote.server.repository.NotebookRepository
import zio.{Has, IO, Task, URIO, ZIO, ZLayer, ZManaged}
import zio.blocking.{Blocking, effectBlocking}
import sun.net.www.MimeTable

class Server {
  private lazy val currentPath = new File(System.getProperty("user.dir")).toPath
  private lazy val staticWatchPath = currentPath.resolve(s"polynote-frontend/dist")
  private lazy val defaultStaticPath = currentPath

  private def staticFilePath(filename: String, base: Path): IO[HTTPError, java.nio.file.Path] = {
    val pieces = filename.split('/').drop(1).filterNot(_ == "..")
    if (pieces.isEmpty)
      ZIO.fail(NotFound(filename))
    else
      ZIO.succeed(base.resolve(Paths.get(pieces.head, pieces.tail: _*)))
  }

  private def indexFileContent(key: String): URIO[MainArgs with Config, URIO[Blocking, String]] =
    Config.access.flatMap { config =>
      ZIO.access[MainArgs](_.get.watchUI).flatMap { watchUI =>
        val staticUri = config.static.url.map(_.toString).getOrElse("static")
        val staticPath = if (watchUI) staticWatchPath else config.static.path.getOrElse(defaultStaticPath)

        val is = effectBlocking {
          java.nio.file.Files.newInputStream(staticPath.resolve("static").resolve("index.html"))
        }

        val content = is.bracket(is => effectBlocking(is.close()).orDie) {
          is => effectBlocking(scala.io.Source.fromInputStream(is, "UTF-8").mkString
            .replace("$WS_KEY", key.toString)
            .replace("$BASE_URI", config.ui.baseUri)
            .replace("\"static/", s""""$staticUri/"""))
        }.orDie

        content match {
          case content if watchUI => ZIO.succeed(content)
          case content            => content.memoize
        }
      }
    }

  private val securityWarning =
    """Polynote allows arbitrary remote code execution, which is necessary for a notebook tool to function.
      |While we'll try to improve safety by adding security measures, it will never be completely safe to
      |run Polynote on your personal computer. For example:
      |
      |- It's possible that other websites you visit could use Polynote as an attack vector. Browsing the web
      |  while running Polynote is unsafe.
      |- It's possible that remote attackers could use Polynote as an attack vector. Running Polynote on a
      |  computer that's accessible from the internet is unsafe.
      |- Even running Polynote inside a container doesn't guarantee safety, as there will always be
      |  privilege escalation and container escape vulnerabilities which an attacker could leverage.
      |
      |Please be diligent about checking for new releases, as they could contain fixes for critical security
      |flaws.
      |
      |Please be mindful of the security issues that Polynote causes; consult your company's security team
      |before running Polynote. You are solely responsible for any breach, loss, or damage caused by running
      |this software insecurely.""".stripMargin

  private val banner: String =
    raw"""|
          |  _____      _                   _
          | |  __ \    | |                 | |
          | | |__) |__ | |_   _ _ __   ___ | |_ ___
          | |  ___/ _ \| | | | | '_ \ / _ \| __/ _ \
          | | |  | (_) | | |_| | | | | (_) | ||  __/
          | |_|   \___/|_|\__, |_| |_|\___/ \__\___|
          |                __/ |
          |               |___/
          |
          |""".stripMargin


  def main: ZIO[AppEnv, String, Int] = {
    for {
      config       <- ZIO.access[Config](_.get[PolynoteConfig])
      _            <- Logging.info(s"Loaded configuration: $config")
      wsKey         = config.security.websocketKey.getOrElse(UUID.randomUUID().toString)
      _            <- Logging.warn(securityWarning)
      _            <- Logging.info(banner)
      _            <- Logging.info(s"Polynote version ${BuildInfo.version}")
      _            <- serve(wsKey).orDie
    } yield 0
  }.provideSomeLayer[AppEnv](IdentityProvider.layer.orDie)


  type MainEnv = GlobalEnv with IdentityProvider with Has[NotebookRepository]
  type RequestEnv = BaseEnv with MainEnv with NotebookManager

  private def downloadFile(path: String, req: Request): ZIO[RequestEnv, HTTPError, Response] = {
    NotebookManager.fetchIfOpen(path).flatMap {
      case Some((mime, content)) =>
        effectBlocking(Response.const(content.getBytes(StandardCharsets.UTF_8), contentType = mime))
      case None =>
        for {
          uri <- NotebookManager.location(path).someOrFail(NotFound(req.uri.toString))
          loc <- effectBlocking(Paths.get(uri)) // eventually we'll have to deal with other schemes here
          rep <- Response.fromPath(
            loc, req,
            "application/x-ipynb+json",
            headers = List("Content-Disposition" -> s"attachment; filename=${URLEncoder.encode(loc.getFileName.toString, "utf-8")}"))
        } yield rep
    }
  }.orElseFail(NotFound(req.uri.toString))

  def serve(wsKey: String): ZIO[BaseEnv with MainEnv with MainArgs, Throwable, Unit] =
    server(wsKey).use {
      server => server.awaitShutdown
    }

  def server(
    wsKey: String
  ): ZManaged[BaseEnv with MainEnv with MainArgs, Throwable, uzhttp.server.Server] = Config.access.toManaged_.flatMap { config =>
    ZManaged.access[MainArgs](_.get[Args].watchUI).flatMap { watchUI =>
      val staticPath = if (watchUI) staticWatchPath else config.static.path.getOrElse(defaultStaticPath)

      def serveFile(name: String, req: Request) = {
        val mimeType = Server.MimeTypes.get(name)
        val gzipped = staticFilePath(s"$name.gz", staticPath).flatMap {
          path => Response.fromPath(path, req, contentType = mimeType, headers = List("Content-Encoding" -> "gzip")).map(_.withCacheControl)
        }

        val nogzip = staticFilePath(name, staticPath).flatMap {
          path => Response.fromPath(path, req, contentType = mimeType).map(_.withCacheControl)
        }

        val fromJar = Response.fromResource(name.drop(1), req, contentType = Server.MimeTypes.get(name)).map(_.withCacheControl)

        gzipped orElse nogzip orElse fromJar
      }.catchAll {
        case err: HTTPError => ZIO.fail(err)
        case err => Logging.error("Error serving file", err) *> ZIO.fail(InternalServerError("Error serving file", Some(err)))
      }

      val serveStatic: PartialFunction[Request, ZIO[RequestEnv, HTTPError, Response]] = {
        case req if req.uri.getPath == "/favicon.ico" => serveFile("/static/favicon.ico", req)
        case req if req.uri.getPath startsWith "/static/" => serveFile(req.uri.getPath, req)
      }

      val staticFiles: ZManaged[RequestEnv, Nothing, PartialFunction[Request, ZIO[RequestEnv, HTTPError, Response]]] =
        if (watchUI) {
          ZManaged.succeed(serveStatic)
        } else {
          Response.permanentCache
            .handleSome(serveStatic)
            .build
        }

      for {
        authRoutes    <- IdentityProvider.authRoutes.toManaged_
        broadcastAll  <- Topic[Task, Option[Message]](None).toManaged_  // used to broadcast messages to all connected clients
        _             <- Env.addManagedLayer(NotebookManager.layer[BaseEnv with MainEnv with MainArgs](broadcastAll))
        authorize     <- IdentityProvider.authorize[RequestEnv].toManaged_
        staticHandler <- staticFiles
        address       <- ZIO(config.listen.toSocketAddress).toManaged_
        getIndex      <- indexFileContent(wsKey).toManaged_
        server        <- uzhttp.server.Server.builder(address).handleSome {
          case req@Request.WebsocketRequest(_, uri, _, _, inputFrames) =>
            val path = uri.getPath
            val query = uri.getQuery
            if ((path startsWith "/ws") && (query == s"key=$wsKey")) {
              path.stripPrefix("/ws").stripPrefix("/") match {
                case "" => authorize(req, SocketSession(inputFrames, broadcastAll).flatMap(output => Response.websocket(req, output)))
                case rest => authorize(req, NotebookSession.stream(rest, inputFrames).flatMap(output => Response.websocket(req, output)))
              }
            } else ZIO.fail(Forbidden("Missing or incorrect key"))
        }.handleSome {
          case req if req.uri.getPath == "/" || req.uri.getPath == "" => getIndex.map(Response.html(_))
          case req if req.uri.getPath startsWith "/notebook/" =>
            req.uri.getQuery match {
              case "download=true" => downloadFile(req.uri.getPath.stripPrefix("/notebook/"), req)
              case _ => getIndex.map(Response.html(_))
            }
        } .handleSome(staticHandler)
          .handleSome(authRoutes)
          .logRequests(ServerLogger.noLogRequests)
          .logErrors((msg, err) => Logging.error(msg, err))
          .logInfo(msg => Logging.info(msg))
          .serve
      } yield server
    }
  }

}

object Server {
  type Routes = PartialFunction[Request, ZIO[BaseEnv with Config, HTTPError, Response]]

  object MimeTypes {
    private val fromSystem = MimeTable.getDefaultTable
    private val explicit = Map(
      ".png" -> "image/png",
      ".js" -> "application/javascript",
      ".map" -> "application/json",
      ".css" -> "text/css",
      ".ico" -> "image/x-icon",
      ".otf" -> "font/otf",
      ".svg" -> "image/svg+xml",
      ".woff" -> "font/woff",
      ".woff2" -> "font/woff2"
    )

    def get(filename: String): String = {
      filename.lastIndexOf('.') match {
        case -1 => "application/octet-stream"
        case n  => explicit.get(filename.substring(n).toLowerCase()) orElse Option(fromSystem.getContentTypeFor(filename)) getOrElse "application/octet-stream"
      }
    }
  }
}
