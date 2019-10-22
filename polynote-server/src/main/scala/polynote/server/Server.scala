package polynote.server

import java.io.File

import org.http4s.{HttpApp, HttpRoutes, Request, Response, StaticFile}
import org.http4s.blaze.pipeline.Command.EOF
import org.http4s.dsl.Http4sDsl
import org.http4s.server.blaze.BlazeServerBuilder
import polynote.config.PolynoteConfig
import polynote.kernel.environment.{Config, Env}
import polynote.kernel.logging.Logging
import polynote.kernel.{BaseEnv, GlobalEnv, Kernel, LocalKernel, interpreter}
import zio.{Cause, Runtime, Task, RIO, ZIO, system}
import zio.interop.catz._
import zio.interop.catz.implicits._
import zio.random.Random

import scala.annotation.tailrec

class Server(kernelFactory: Kernel.Factory.Service) extends polynote.app.App with Http4sDsl[Task] {

  private val blockingEC = unsafeRun(Environment.blocking.blockingExecutor).asEC

  private val indexFile = "/index.html"

  override def reportFailure(cause: Cause[_]): Unit = cause.failures.distinct match {
    case List(EOF) => ()  // unable to otherwise silence this error that happens whenever websocket is closed by client
    case other     => super.reportFailure(cause)
  }

  override def run(args: List[String]): ZIO[Environment, Nothing, Int] = for {
    args     <- ZIO.fromEither(Server.parseArgs(args)).orDie
    _        <- Logging.info(s"Loading configuration from ${args.configFile}")
    config   <- PolynoteConfig.load(args.configFile).orDie
    port      = config.listen.port
    address   = config.listen.host
    host      = if (address == "0.0.0.0") java.net.InetAddress.getLocalHost.getHostAddress else address
    url       = s"http://$host:$port"
    interps  <- interpreter.Loader.load.orDie
    globalEnv = Env.enrichWith[BaseEnv, GlobalEnv](Environment, GlobalEnv(config, interps, kernelFactory))
    manager  <- NotebookManager().provide(globalEnv).orDie
    socketEnv = Env.enrichWith[BaseEnv with GlobalEnv, NotebookManager](globalEnv, manager)
    app      <- httpApp(args.watchUI).provide(socketEnv).orDie
    exit     <- BlazeServerBuilder[Task]
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

  def httpApp(watchUI: Boolean): RIO[BaseEnv with GlobalEnv with NotebookManager, HttpApp[Task]] = for {
    env <- ZIO.access[BaseEnv with GlobalEnv with NotebookManager](identity)
  } yield HttpRoutes.of[Task] {
    case GET -> Root / "ws" => SocketSession().flatMap(_.toResponse).provide(env)
    case req @ GET -> Root  => serveFile(indexFile, req, watchUI)
    case req @ GET -> "notebook" /: path :? DownloadMatcher(Some("true")) =>
      downloadFile(path.toList.mkString("/"), req, env.polynoteConfig)
    case req @ GET -> "notebook" /: _ => serveFile(indexFile, req, watchUI)
    case req @ GET -> (Root / "polynote-assembly.jar") =>
      StaticFile.fromFile[Task](new File(getClass.getProtectionDomain.getCodeSource.getLocation.getPath), blockingEC).getOrElseF(NotFound())
    case req @ GET -> path  =>
      serveFile(path.toString, req, watchUI)
  }.mapF(_.getOrElseF(NotFound()))

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
