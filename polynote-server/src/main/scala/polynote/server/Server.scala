package polynote.server

import java.io.File
import java.net.URL
import java.nio.file.Files
import java.util.Date

import cats.effect._
import cats.implicits._
import org.http4s._
import org.http4s.dsl.Http4sDsl
import org.http4s.server.blaze.BlazeBuilder
import polynote.buildinfo.BuildInfo
import polynote.config.{PolyLogger, PolynoteConfig}
import polynote.server.repository.NotebookRepository
import polynote.server.repository.ipynb.IPythonNotebookRepository

import scala.concurrent.ExecutionContext

trait Server extends IOApp with Http4sDsl[IO] with KernelLaunching {

  // TODO: obviously, clean this up
  private val indexFile = "/index.html"

  private implicit val executionContext: ExecutionContext = ExecutionContext.global  // TODO: use a real one

  private val logger = new PolyLogger

  def serveFile(path: String, req: Request[IO], watchUI: Boolean)(implicit syncIO: Sync[IO]): IO[Response[IO]] = {
    if (watchUI) {
      val outputLoc = new File(System.getProperty("user.dir")).toPath.resolve(s"polynote-frontend/dist/$path").toString
      StaticFile.fromString(outputLoc, executionContext, Some(req)).getOrElseF(NotFound())
    } else {
      StaticFile.fromResource(path, executionContext, Some(req)).getOrElseF(NotFound())
    }
  }

  def downloadFile(path: String, req: Request[IO], config: PolynoteConfig): IO[Response[IO]] = {
    val nbLoc = new File(System.getProperty("user.dir")).toPath.resolve(s"${config.storage.dir}/$path").toString
    StaticFile.fromString(nbLoc, executionContext, Some(req)).getOrElseF(NotFound())
  }

  object DownloadMatcher extends OptionalQueryParamDecoderMatcher[String]("download")

  def route(notebookManager: NotebookManager[IO], config: PolynoteConfig, watchUI: Boolean)(implicit timer: Timer[IO]): HttpRoutes[IO] = {
    HttpRoutes.of[IO] {
      case GET -> Root / "ws" => SocketSession(notebookManager).flatMap(_.toResponse)
      case req @ GET -> Root  => serveFile(indexFile, req, watchUI)
      case req @ GET -> "notebook" /: path :? DownloadMatcher(Some("true")) =>
        IO(logger.info(s"Download request for ${req.pathInfo} from ${req.remoteAddr}")) *> downloadFile(path.toList.mkString("/"), req, config)
      case req @ GET -> "notebook" /: _ => serveFile(indexFile, req, watchUI)
      case req @ GET -> (Root / "polynote-assembly.jar") =>
        StaticFile.fromFile[IO](new File(getClass.getProtectionDomain.getCodeSource.getLocation.getPath), executionContext).getOrElseF(NotFound())
      case req @ GET -> path  =>
        serveFile(path.toString, req, watchUI)
    }
  }

  protected def splash: IO[Unit] = IO {
    logger.info(
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
           |""".stripMargin)
  }

  protected def createRepository(config: PolynoteConfig): NotebookRepository[IO] = new IPythonNotebookRepository(
    new File(System.getProperty("user.dir")).toPath.resolve(config.storage.dir),
    config,
    executionContext = executionContext)

  /**
    * This is an icky thing we have to do to prevent certain *ahem* bad logging citizens from breaking. Find the log4j
    * configuration and reset the system property to be a URI version of it rather than a relative path.
    *
    * This is probably not a good way to deal with this and is going to lead to lots of other problems. We should try
    * to fix the actual issue in the libraries that have it.
    */
  protected def adjustSystemProperties(): IO[Unit] = IO {
    System.getProperty("log4j.configuration") match {
      case null =>
      case loc =>
        val asURL = try new URL(loc) catch {
          case err: Throwable => getClass.getClassLoader.getResource(loc)
        }
        logger.info(s"Resetting log4j.configuration to $asURL")
        System.setProperty("log4j.configuration", asURL.toString)
    }
  }

  def getConfigs(args: List[String]): IO[(ServerArgs, PolynoteConfig)] = for {
    args            <- parseArgs(args)
    config          <- PolynoteConfig.load(args.configFile)
  } yield (args, config)

  def createDir(dir: String): IO[Unit] = IO {
    Files.createDirectory(new File(dir).toPath)
  }

  def run(args: List[String]): IO[ExitCode] = for {
    tuple           <- getConfigs(args)
    (args, config)   = tuple // tuple decomposition in for-comprehension doesn't seem work I guess...
    port             = config.listen.port
    address          = config.listen.host
    _               <- createDir(config.storage.dir)
    _               <- IO(logger.debug("Debug logging is ON"))
    _               <- IO(logger.info(s"Read config from ${args.configFile.getAbsolutePath}: $config"))
    _               <- adjustSystemProperties()
    host             = if (address == "0.0.0.0") java.net.InetAddress.getLocalHost.getHostAddress else address
    url              = s"http://$host:$port"
    _               <- splash
    _               <- IO(logger.info(s" Version is ${BuildInfo.version}, built at ${new Date(BuildInfo.buildTime)}"))
    _               <- IO(logger.info(s" Running on $url"))
    repository       = createRepository(config)
    notebookManager  = new IONotebookManager(config, repository, kernelFactory)

    exitCode        <- BlazeBuilder[IO]
                        .bindHttp(port, address)
                        .withWebSockets(true)
                        .mountService(route(notebookManager, config, args.watchUI), "/")
                        .serve
                        .compile
                        .toList
                        .map(_.head)
  } yield exitCode

  protected def parseArgs(args: List[String]): IO[ServerArgs] = IO.fromEither(Server.parseArgs(args))

}

object Server {

  case class Args(
    configFile: File = new File("config.yml"),
    watchUI: Boolean = false
  ) extends ServerArgs

  private val serverClass = """polynote.server.(.*)""".r
  private def parseArgs(args: List[String], current: Args = Args()): Either[Throwable, Args] = args match {
    case Nil => Right(current)
    case ("--config" | "-c") :: filename :: rest => parseArgs(rest, current.copy(configFile = new File(filename)))
    case ("--watch"  | "-w") :: rest => parseArgs(rest, current.copy(watchUI = true))
    case serverClass(_) :: rest => parseArgs(rest, current) // class name might be arg0 in some circumstances
    case other :: rest => Left(new IllegalArgumentException(s"Unknown argument $other"))
  }

}

trait ServerArgs {
  def configFile: File
  def watchUI: Boolean
}


object ServerApp extends Server