package polynote.server

import java.io.{BufferedReader, File, FileInputStream, FileNotFoundException, InputStreamReader}
import java.net.InetSocketAddress
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.{ExecutorService, Executors, ThreadFactory, TimeUnit}

import fs2.{Chunk, Stream}
import fs2.concurrent.Topic
import cats.instances.option._
import cats.syntax.traverse._
import polynote.buildinfo.BuildInfo
import polynote.config.PolynoteConfig
import polynote.kernel.environment.{Config, Env}
import polynote.kernel.logging.Logging
import polynote.kernel.{BaseEnv, GlobalEnv, Kernel, interpreter}
import polynote.messages.{Error, Message}
import polynote.server.auth.{Identity, IdentityProvider, UserIdentity}
import uzhttp.server.ServerLogger
import uzhttp.{HTTPError, Request, Response}, HTTPError.{Forbidden, NotFound}
import zio.{Cause, Has, IO, RIO, Task, URIO, ZIO, ZLayer}
import zio.blocking.{Blocking, effectBlocking}

import scala.annotation.tailrec
import sun.net.www.MimeTable

import scala.concurrent.ExecutionContext

class Server(kernelFactory: Kernel.Factory.Service) extends polynote.app.App {
  private lazy val staticPath = new File(System.getProperty("user.dir")).toPath.resolve(s"polynote-frontend/dist")
  private lazy val watchUIPath = new File(System.getProperty("user.dir")).toPath.resolve(s"polynote-frontend/dist/index.html")

  private def staticFilePath(filename: String): IO[HTTPError, java.nio.file.Path] = {
    val pieces = filename.split('/').drop(1).filterNot(_ == "..")
    if (pieces.isEmpty)
      ZIO.fail(NotFound(filename))
    else
      ZIO.succeed(staticPath.resolve(Paths.get(pieces.head, pieces.tail: _*)))
  }

  private def indexFileContent(key: String, config: PolynoteConfig, watchUI: Boolean) = {
    val is = effectBlocking {
      if (watchUI) {
        Some(java.nio.file.Files.newInputStream(watchUIPath))
      } else {
        Option(getClass.getClassLoader.getResourceAsStream("index.html"))
      }
    }.someOrFail(new RuntimeException("Failed to load polynote frontend"))

    val content = is.bracket(is => effectBlocking(is.close()).orDie) {
      is => effectBlocking(scala.io.Source.fromInputStream(is, "UTF-8").mkString
        .replace("$WS_KEY", key.toString)
        .replace("$BASE_URI", config.ui.baseUri))
    }.orDie

    content match {
      case content if watchUI => ZIO.succeed(content)
      case content            => content.memoize
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

  override def main(args: List[String]): ZIO[Environment, Nothing, Int] = {
    for {
      args         <- ZIO.fromEither(Server.parseArgs(args)).orDie
      _            <- Logging.info(s"Loading configuration from ${args.configFile}")
      config       <- PolynoteConfig.load(args.configFile).orDie
      _            <- Logging.info(s"Loaded configuration: $config")
      port          = config.listen.port
      address       = config.listen.host
      wsKey         = config.security.websocketKey.getOrElse(UUID.randomUUID().toString)
      host          = if (address == "0.0.0.0") java.net.InetAddress.getLocalHost.getHostAddress else address
      interps      <- interpreter.Loader.load.orDie
      broadcastAll <- Topic[Task, Option[Message]](None).orDie  // used to broadcast messages to all connected clients
      _            <- Env.addMany[BaseEnv](GlobalEnv(config, interps, kernelFactory))
      _            <- Env.addM[BaseEnv with GlobalEnv](NotebookManager(broadcastAll).orDie)
      loadIndex    <- indexFileContent(wsKey, config, args.watchUI)
      _            <- Env.addM[BaseEnv with GlobalEnv with NotebookManager](IdentityProvider.load.orDie)
      _            <- Logging.warn(securityWarning)
      handler      <- mkHandler(args.watchUI, wsKey, loadIndex, broadcastAll)
      _            <- Logging.info(banner)
      _            <- uzhttp.server.Server.builder(new InetSocketAddress(host, port))
          .handleAll(handler)
          .logRequests(ServerLogger.noLogRequests)
          .logErrors((msg, err) => Logging.error(msg, err))
          .logInfo(msg => Logging.info(msg))
          .serve.use {
            server => server.awaitShutdown
          }.orDie
    } yield 0
  }
  
  type RequestEnv = BaseEnv with GlobalEnv with NotebookManager with IdentityProvider

  def mkHandler(
    watchUI: Boolean,
    wsKey: String,
    getIndex: URIO[Blocking, String],
    broadcastAll: Topic[Task, Option[Message]]
  ): URIO[RequestEnv with IdentityProvider, Request => ZIO[RequestEnv, HTTPError, Response]] = {
    for {
      authRoutes <- IdentityProvider.authRoutes
      authorize  <- IdentityProvider.authorize[RequestEnv]
    } yield (req: Request) => req match {
      case req@Request.WebsocketRequest(_, uri, _, _, inputFrames) =>
        val path = uri.getPath
        val query = uri.getQuery
        if ((path startsWith "/ws") && (query == s"key=$wsKey")) {
          path.stripPrefix("/ws") match {
            case "" =>   authorize(req, SocketSession(inputFrames, broadcastAll).flatMap(output => Response.websocket(req, output)))
            case rest => authorize(req, NotebookSession.stream(rest, inputFrames).flatMap(output => Response.websocket(req, output)))
          }
        } else ZIO.fail(Forbidden("Missing or incorrect key"))
      case req =>
        def serveFile(name: String) = if (watchUI) {
          staticFilePath(name).flatMap {
            path =>
              Response.fromPath(path, req, contentType = Server.MimeTypes.get(name))
          }
        } else {
          Response.fromResource(name.drop(1), req, contentType = Server.MimeTypes.get(name))
        }

        req.uri.getPath match {
          case "/" | "" => getIndex.map(Response.html(_))
          case uri if uri startsWith "/notebook" =>
            req.uri.getQuery match {
              case "download" => ???
              case _ => getIndex.map(Response.html(_))
            }
          case uri      => serveFile(uri).orElse(authRoutes.lift(req).getOrElse(ZIO.fail(NotFound(uri))))
        }
    }
  }
}

object Server {
  type Routes = PartialFunction[Request, ZIO[BaseEnv with Config, HTTPError, Response]]
  case class Args(
    configFile: File = new File("config.yml"),
    watchUI: Boolean = false
  )

  private val serverClass = """polynote.server.(.*)""".r

  @tailrec
  private def parseArgs(args: List[String], current: Args = Args()): Either[Throwable, Args] = args match {
    case Nil => Right(current)
    case ("--config" | "-c") :: filename :: rest => parseArgs(rest, current.copy(configFile = new File(filename)))
    case ("--watch"  | "-w") :: rest => parseArgs(rest, current.copy(watchUI = true))
    case serverClass(_) :: rest => parseArgs(rest, current) // class name might be arg0 in some circumstances
    case other :: rest => Left(new IllegalArgumentException(s"Unknown argument $other"))
  }

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
