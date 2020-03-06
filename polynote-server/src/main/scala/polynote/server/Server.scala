package polynote.server

import java.io.{BufferedReader, File, FileInputStream, FileNotFoundException, InputStreamReader}
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.{ExecutorService, Executors, ThreadFactory, TimeUnit}

import fs2.{Chunk, Stream}
import fs2.concurrent.Topic
import cats.instances.option._
import cats.syntax.traverse._
import org.http4s.{Charset, Headers, HttpApp, HttpRoutes, MediaType, Request, Response, StaticFile, Status}
import org.http4s.blaze.pipeline.Command.EOF
import org.http4s.dsl.Http4sDsl
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.headers.{`Content-Length`, `Content-Type`}
import polynote.buildinfo.BuildInfo
import polynote.config.PolynoteConfig
import polynote.kernel.environment.{Config, Env}
import polynote.kernel.logging.Logging
import polynote.kernel.{BaseEnv, GlobalEnv, Kernel, interpreter}
import polynote.messages.{Error, Message}
import polynote.server.auth.{Identity, IdentityProvider, UserIdentity}
import zio.{Cause, Has, RIO, Task, ZIO, ZLayer}
import zio.blocking.{Blocking, effectBlocking}

import scala.annotation.tailrec
import scala.collection.immutable.StringOps
import Server.Routes
import cats.effect.ConcurrentEffect
import polynote.server.repository.format.FormatProviderNotFound

import scala.concurrent.ExecutionContext

class Server(kernelFactory: Kernel.Factory.Service) extends polynote.app.App with Http4sDsl[Task] {
  private implicit val taskConcurrentEffect: ConcurrentEffect[Task] = zio.interop.catz.taskEffectInstance[Any]
  private lazy val watchUIPath = new File(System.getProperty("user.dir")).toPath.resolve(s"polynote-frontend/dist/index.html")
  type FS = Has[Server.Http4sFilesystem]
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
      url           = s"http://$host:$port"
      interps      <- interpreter.Loader.load.orDie
      broadcastAll <- Topic[Task, Option[Message]](None).orDie  // used to broadcast messages to all connected clients
      _            <- Env.addMany[BaseEnv with FS](GlobalEnv(config, interps, kernelFactory))
      _            <- Env.addM[BaseEnv with GlobalEnv with FS](NotebookManager(broadcastAll).orDie)
      loadIndex    <- indexFileContent(wsKey, config, args.watchUI)
      _            <- Env.addM[BaseEnv with GlobalEnv with NotebookManager with FS](IdentityProvider.load.orDie)
      app          <- httpApp(args.watchUI, wsKey, loadIndex, broadcastAll).orDie
      _            <- Logging.warn(securityWarning)
      exit         <- BlazeServerBuilder[Task]
        .withBanner(
          new StringOps(raw"""
                             |
                             |  _____      _                   _
                             | |  __ \    | |                 | |
                             | | |__) |__ | |_   _ _ __   ___ | |_ ___
                             | |  ___/ _ \| | | | | '_ \ / _ \| __/ _ \
                             | | |  | (_) | | |_| | | | | (_) | ||  __/
                             | |_|   \___/|_|\__, |_| |_|\___/ \__\___|
                             |                __/ |
                             |               |___/
                             |
                             |Server running at $url
                             |""".stripMargin).lines.toList)
        .bindHttp(port, address)
        .withWebSockets(true)
        .withNio2(true)
        .withHttpApp(app)
        .serve.compile.last.orDie
    } yield exit.map(_.code).getOrElse(0)
  }.provideSomeLayer[Environment](Server.fsLayer(NotFound()))


  def serveFile(path: String, req: Request[Task], watchUI: Boolean): RIO[Has[Server.Http4sFilesystem], Response[Task]] = ZIO.access[Has[Server.Http4sFilesystem]](_.get).flatMap {
    fs =>
      if (watchUI) {
        val outputLoc = new File(System.getProperty("user.dir")).toPath.resolve(s"polynote-frontend/dist/$path").toString
        fs.staticFile(outputLoc, req)
      } else {
        fs.staticResource(path, req)
      }
  }

  def staticFile(path: String, req: Request[Task]): RIO[Has[Server.Http4sFilesystem], Response[Task]] =
    ZIO.accessM[Has[Server.Http4sFilesystem]](_.get.staticFile(path, req))

  def staticResource(path: String, req: Request[Task]): RIO[Has[Server.Http4sFilesystem], Response[Task]] =
    ZIO.accessM[Has[Server.Http4sFilesystem]](_.get.staticResource(path, req))

  def downloadFile(path: String, req: Request[Task]): ZIO[BaseEnv with GlobalEnv with NotebookManager with Has[Server.Http4sFilesystem], Throwable, Response[Task]] = for {
    notebookManager <- ZIO.access[NotebookManager](_.get)
    nbURI           <- notebookManager.location(path).someOrFail(new FileNotFoundException(s"Unable to find notebook with path: $path"))
    nbLoc            = new File(nbURI).toString // eventually we'll have to deal with other schemes heret
    result          <- staticFile(nbLoc, req).onError(err => Logging.error("Error downloading file", err))
  } yield result

  object DownloadMatcher extends OptionalQueryParamDecoderMatcher[String]("download")
  object KeyMatcher extends QueryParamDecoderMatcher[String]("key")

  type RequestEnv = BaseEnv with GlobalEnv with NotebookManager with IdentityProvider

  def httpApp(
    watchUI: Boolean,
    wsKey: String,
    getIndex: ZIO[Blocking, Nothing, String],
    broadcastAll: Topic[Task, Option[Message]]
  ): RIO[RequestEnv with Has[Server.Http4sFilesystem], HttpApp[Task]] = ZIO.access[Has[Server.Http4sFilesystem]](_.get).flatMap {
    fs =>
      val indexResponse = getIndex.map {
        index =>
          val indexBytes = index.getBytes(StandardCharsets.UTF_8)
          Response[Task](
            headers = Headers.of(`Content-Type`(MediaType.text.html, Charset.`UTF-8`), `Content-Length`.unsafeFromLong(indexBytes.length)),
            body = fs2.Stream.chunk(Chunk.bytes(indexBytes))
          )
      }

      def toResponse(msg: Message, status: Status): Response[Task] =
        Response(
          status = status,
          body = Stream.eval(Message.encode[Task](msg)).flatMap {
            bitVector => Stream.chunk(Chunk.byteVector(bitVector.toByteVector))
          }
        ).withContentType(`Content-Type`(MediaType.application.`octet-stream`))

      def notebookSession(path: String): RIO[SessionEnv with NotebookManager, Response[Task]] = {
          NotebookSession(path).flatMap(_.toResponse) <* UserIdentity.access.flatMap(user => Logging.info(s"Beginning notebook session $path for user $user"))
      }.catchAll {
        case err: FileNotFoundException => ZIO.succeed(toResponse(Error(404, err), NotFound))
        case err => ZIO.succeed(toResponse(Error(0, err), InternalServerError))
      }

      for {
        env        <- ZIO.access[RequestEnv](identity)
        authRoutes <- IdentityProvider.authRoutes
        authorize  <- IdentityProvider.authorize[RequestEnv]
      } yield HttpRoutes.of[Task] {

        val defaultRoutes: Routes = {
          case req @ GET -> Root / "ws" :? KeyMatcher(`wsKey`)                  => authorize(req, SocketSession(broadcastAll).flatMap(_.toResponse)).provide(env)
          case req @ GET -> "ws" /: path :? KeyMatcher(`wsKey`)                 => authorize(req, notebookSession(path.toList.mkString("/"))).provide(env)
          case GET -> Root / "ws"                                               => Forbidden()
          case req @ GET -> Root                                                => indexResponse.provide(env)
          case req @ GET -> "notebook" /: path :? DownloadMatcher(Some("true")) => downloadFile(path.toList.mkString("/"), req).provide(env ++ Has(fs))
          case req @ GET -> "notebook" /: _                                     => indexResponse.provide(env)
          case req @ GET -> (Root / "polynote-assembly.jar")                    => fs.serveSelf
        }

        (defaultRoutes orElse authRoutes).andThen(_.provide(env)) orElse {
          case req @ GET -> path => serveFile(path.toString, req, watchUI).provide(Has(fs))
        }
      }.mapF(_.getOrElseF(NotFound()))
  }
}

object Server {
  type Routes = PartialFunction[Request[Task], RIO[BaseEnv with Config, Response[Task]]]
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

  class Http4sFilesystem(blockingEC: ExecutionContext, NotFound: Task[Response[Task]]) {
    def staticFile(location: String, req: Request[Task]): Task[Response[Task]] =
      StaticFile.fromString[Task](location, blockingEC, Some(req)).getOrElseF(NotFound)

    def staticResource(path: String, req: Request[Task]): Task[Response[Task]] =
      StaticFile.fromResource(path, blockingEC, Some(req)).getOrElseF(NotFound)

    def serveSelf: Task[Response[Task]] =
      StaticFile.fromFile[Task](new File(getClass.getProtectionDomain.getCodeSource.getLocation.getPath), blockingEC).getOrElseF(NotFound)
  }

  def fsLayer(NotFound: Task[Response[Task]]): ZLayer[Blocking, Nothing, Has[Http4sFilesystem]] = ZLayer.fromService {
    blocking: Blocking.Service => new Http4sFilesystem(blocking.blockingExecutor.asEC, NotFound)
  }
}
