package polynote.server

import java.io.File
import java.util.ServiceLoader

import cats.effect._
import org.http4s._
import org.http4s.dsl.Http4sDsl
import org.http4s.server.blaze.BlazeBuilder
import org.log4s.{Logger, getLogger}
import polynote.kernel.dependency.CoursierFetcher
import polynote.kernel.lang.{LanguageInterpreter, LanguageInterpreterService}
import polynote.server.repository.NotebookRepository
import polynote.server.repository.ipynb.IPythonNotebookRepository

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext

trait Server extends IOApp with Http4sDsl[IO] {

  // TODO: obviously, clean this up
  private val indexFile = "/index.html"

  private implicit val executionContext: ExecutionContext = ExecutionContext.global  // TODO: use a real one

  protected val logger: Logger = getLogger
  protected val dependencyFetcher = new CoursierFetcher()

  protected val interpreters: Map[String, LanguageInterpreter.Factory[IO]] =
    ServiceLoader.load(classOf[LanguageInterpreterService]).iterator.asScala.toSeq
      .sortBy(_.priority)
      .foldLeft(Map.empty[String, LanguageInterpreter.Factory[IO]]) {
        (accum, next) => accum ++ next.interpreters
      }

  protected lazy val kernelFactory: KernelFactory[IO] = new IOKernelFactory(Map("scala" -> dependencyFetcher), interpreters)

  def serveFile(path: String, req: Request[IO], watchUI: Boolean)(implicit syncIO: Sync[IO]): IO[Response[IO]] = {
    if (watchUI) {
      val outputLoc = new File(System.getProperty("user.dir")).toPath.resolve(s"polynote-frontend/dist/$path").toString
      StaticFile.fromString(outputLoc, executionContext, Some(req)).getOrElseF(NotFound())
    } else {
      StaticFile.fromResource(path, executionContext, Some(req)).getOrElseF(NotFound())
    }
  }

  def route(notebookManager: NotebookManager[IO], watchUI: Boolean)(implicit timer: Timer[IO]): HttpRoutes[IO] = {
    HttpRoutes.of[IO] {
      case GET -> Root / "ws" => SocketSession(notebookManager).flatMap(_.toResponse)
      case req @ GET -> Root  => serveFile(indexFile, req, watchUI)
      case req @ GET -> "notebook" /: _ => serveFile(indexFile, req, watchUI)
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

  protected def createRepository(serverConfig: ServerConfig): NotebookRepository[IO] = new IPythonNotebookRepository(
    new File(System.getProperty("user.dir")).toPath.resolve("notebooks"),
    serverConfig,
    executionContext = executionContext)

  def run(args: List[String]): IO[ExitCode] = for {
    // note, by default our bdas genie script sets log4j.configuration to a nonexistent log4j config. We should either
    // create that config or remove that setting. Until then, be sure to add `--driver-java-options "-Dlog4j.configuration=log4j.properties"
    // to your `spark-submit` call.
    args            <- parseArgs(args)
    config          <- ServerConfig.load(args.configFile)
    port             = config.listen.port
    address          = config.listen.address
    host             = if (address == "0.0.0.0") java.net.InetAddress.getLocalHost.getHostAddress else address
    url              = s"http://$host:$port"
    _               <- splash
    _               <- IO(logger.info(s" Running on $url"))
    repository       = createRepository(config)
    notebookManager  = new IONotebookManager(config, repository, kernelFactory)

    exitCode        <- BlazeBuilder[IO]
                        .bindHttp(port, address)
                        .withWebSockets(true)
                        .mountService(route(notebookManager, args.watchUI), "/")
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