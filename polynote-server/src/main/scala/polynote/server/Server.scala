package polynote.server

import java.io.{BufferedReader, File, FileInputStream, InputStreamReader}
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit

import org.http4s.{Charset, Headers, HttpApp, HttpRoutes, MediaType, Request, Response, StaticFile}
import org.http4s.blaze.pipeline.Command.EOF
import org.http4s.dsl.Http4sDsl
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.headers.`Content-Type`
import org.http4s.Status.{BadRequest, Unauthorized}
import org.http4s.util.CaseInsensitiveString
import polynote.config.PolynoteConfig
import polynote.kernel.environment.{Config, Env}
import polynote.kernel.logging.Logging
import polynote.kernel.{BaseEnv, GlobalEnv, Kernel, LocalKernel, interpreter}
import zio.{Cause, IO, RIO, Runtime, Task, UIO, ZIO, system}
import zio.interop.catz._
import zio.interop.catz.implicits._
import zio.random.Random
import zio.blocking.effectBlocking

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer

class Server(kernelFactory: Kernel.Factory.Service) extends polynote.app.App with Http4sDsl[Task] {

  private val blockingEC = unsafeRun(Environment.blocking.blockingExecutor).asEC

  private def indexFileContent(key: UUID, watchUI: Boolean) = {
    val is = ZIO {
      if (watchUI) {
        java.nio.file.Files.newInputStream(
          new File(System.getProperty("user.dir")).toPath.resolve(s"polynote-frontend/dist/index.html"))
      } else {
        getClass.getClassLoader.getResourceAsStream("index.html")
      }
    }

    is.bracket(is => ZIO(is.close()).orDie) {
      is => effectBlocking(scala.io.Source.fromInputStream(is, "UTF-8").mkString.replace("$WS_KEY", key.toString))
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

  override def reportFailure(cause: Cause[_]): Unit = cause.failures.distinct match {
    case List(EOF) => ()  // unable to otherwise silence this error that happens whenever websocket is closed by client
    case other     => super.reportFailure(cause)
  }

  override def run(args: List[String]): ZIO[Environment, Nothing, Int] = for {
    args      <- ZIO.fromEither(Server.parseArgs(args)).orDie
    _         <- Logging.info(s"Loading configuration from ${args.configFile}")
    config    <- PolynoteConfig.load(args.configFile).orDie
    port       = config.listen.port
    address    = config.listen.host
    wsKey      = config.security.websocketKey.getOrElse(UUID.randomUUID())
    host       = if (address == "0.0.0.0") java.net.InetAddress.getLocalHost.getHostAddress else address
    url        = s"http://$host:$port"
    indexHtml <- indexFileContent(wsKey, args.watchUI).orDie
    interps   <- interpreter.Loader.load.orDie
    globalEnv  = Env.enrichWith[BaseEnv, GlobalEnv](Environment, GlobalEnv(config, interps, kernelFactory))
    manager   <- NotebookManager().provide(globalEnv).orDie
    socketEnv  = Env.enrichWith[BaseEnv with GlobalEnv, NotebookManager](globalEnv, manager)
    app       <- httpApp(args.watchUI, wsKey, indexHtml).provide(socketEnv).orDie
    _         <- Logging.warn(securityWarning)
    exit      <- BlazeServerBuilder[Task]
      .withBanner(
        raw"""
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
             |""".stripMargin.lines.toList
      )
      .bindHttp(port, address)
      .withWebSockets(true)
      .withHttpApp(app)
      .serve.compile.last.orDie
  } yield exit.map(_.code).getOrElse(0)


  private def staticFile(location: String, req: Request[Task]): Task[Response[Task]] =
    Environment.blocking.blockingExecutor.flatMap(ec => StaticFile.fromString[Task](location, ec.asEC, Some(req)).getOrElseF(NotFound().provide(Environment)))

  private def staticResource(path: String, req: Request[Task]): Task[Response[Task]] =
    Environment.blocking.blockingExecutor.flatMap(ec => StaticFile.fromResource(path, ec.asEC, Some(req)).getOrElseF(NotFound().provide(Environment)))

  def serveFile(path: String, req: Request[Task], watchUI: Boolean): Task[Response[Task]] = {
    if (watchUI) {
      val outputLoc = new File(System.getProperty("user.dir")).toPath.resolve(s"polynote-frontend/dist/$path").toString
      staticFile(outputLoc, req)
    } else {
      staticResource(path, req)
    }
  }

  def downloadFile(path: String, req: Request[Task], config: PolynoteConfig): Task[Response[Task]] = {
    val nbLoc = new File(System.getProperty("user.dir")).toPath.resolve(s"${config.storage.dir}/$path").toString
    staticFile(nbLoc, req)
  }

  object DownloadMatcher extends OptionalQueryParamDecoderMatcher[String]("download")
  object KeyMatcher extends QueryParamDecoderMatcher[String]("key")

  def httpApp(watchUI: Boolean, wsKey: UUID, indexHtml: String): RIO[BaseEnv with GlobalEnv with NotebookManager, HttpApp[Task]] = {
    val indexResponse = ZIO {
      Response(
        headers = Headers.of(`Content-Type`(MediaType.text.html, Charset.`UTF-8`)),
        body = fs2.Stream.emits[Task, Byte](indexHtml.getBytes(StandardCharsets.UTF_8))
      )
    }

    def checkKey(providedKey: String): IO[UIO[Response[Task]], Unit] = {
      for {
        parsedKey   <- ZIO(UUID.fromString(providedKey)).mapError(_ => Response[Task](status = BadRequest))
        _           <- if (parsedKey == wsKey) ZIO.unit else {
          ZIO.fail(Response[Task](status = Unauthorized)) <* Logging.warn(s"Access attempt with no key")
        }
      } yield ()
    }.mapError {
      response => ZIO.sleep(zio.duration.Duration(500, TimeUnit.MILLISECONDS)).as(response).provide(Environment)
    }.provide(Environment)

    for {
      env <- ZIO.access[BaseEnv with GlobalEnv with NotebookManager](identity)
    } yield HttpRoutes.of[Task] {
      case req @ GET -> Root / "ws" :? KeyMatcher(key)                      => checkKey(key).foldM(_.absorb, _ => SocketSession().flatMap(_.toResponse).provide(env))
      case req @ GET -> Root                                                => indexResponse
      case req @ GET -> "notebook" /: path :? DownloadMatcher(Some("true")) => downloadFile(path.toList.mkString("/"), req, env.polynoteConfig)
      case req @ GET -> "notebook" /: _                                     => indexResponse
      case req @ GET -> (Root / "polynote-assembly.jar")                    => StaticFile.fromFile[Task](new File(getClass.getProtectionDomain.getCodeSource.getLocation.getPath), blockingEC).getOrElseF(NotFound())
      case req @ GET -> path                                                => serveFile(path.toString, req, watchUI)
    }.mapF(_.getOrElseF(NotFound()))
  }

}

object Server {
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
}
