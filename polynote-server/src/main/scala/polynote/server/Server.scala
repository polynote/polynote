package polynote.server

import java.io.File
import java.util.ServiceLoader

import cats.effect._
import org.http4s._
import org.http4s.dsl.Http4sDsl
import org.http4s.server.blaze.BlazeBuilder
import org.log4s.getLogger
import polynote.kernel.dependency.CoursierFetcher
import polynote.kernel.lang.{LanguageInterpreter, LanguageInterpreterService}
import polynote.server.repository.ipynb.IPythonNotebookRepository

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext

trait Server extends IOApp with Http4sDsl[IO] {

  // TODO: obviously, clean this up
  private val indexFile = "/index.html"

  private implicit val executionContext: ExecutionContext = ExecutionContext.global  // TODO: use a real one

  protected val logger = getLogger

  protected val repository = new IPythonNotebookRepository(
    new File(System.getProperty("user.dir")).toPath.resolve("notebooks"),
    executionContext = executionContext)

  protected val dependencyFetcher = new CoursierFetcher()

  protected val subKernels = ServiceLoader.load(classOf[LanguageInterpreterService]).iterator.asScala.toSeq
    .sortBy(_.priority)
    .foldLeft(Map.empty[String, LanguageInterpreter.Factory[IO]]) {
      (accum, next) => accum ++ next.interpreters
    }

  protected lazy val kernelFactory: KernelFactory[IO] = new IOKernelFactory(Map("scala" -> dependencyFetcher), subKernels)

  protected val notebookManager = new IONotebookManager(repository, kernelFactory)

  def serveFile(path: String, req: Request[IO])(implicit syncIO: Sync[IO]): IO[Response[IO]] = {
    StaticFile.fromResource(path, executionContext, Some(req)).getOrElseF(NotFound())
  }

  def route(implicit timer: Timer[IO]): HttpRoutes[IO] = {
    HttpRoutes.of[IO] {
      case GET -> Root / "ws" => SocketSession(notebookManager).flatMap(_.toResponse)
      case req @ GET -> Root  => serveFile(indexFile, req)
      case req @ GET -> "notebook" /: _ => serveFile(indexFile, req)
      case req @ GET -> path  =>
        serveFile(path.toString, req)
    }
  }

  Ok.apply("foo")

  def run(args: List[String]): IO[ExitCode] = {
    // note, by default our bdas genie script sets log4j.configuration to a nonexistent log4j config. We should either
    // create that config or remove that setting. Until then, be sure to add `--driver-java-options "-Dlog4j.configuration=log4j.properties"
    // to your `spark-submit` call.

    val port = 8192 // TODO: replace with proper config eventually
    val hostname = s"http://${java.net.InetAddress.getLocalHost.getHostAddress}:$port"

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
        |
        | Running on $hostname
        |
      """.stripMargin)

    BlazeBuilder[IO]
      .bindHttp(port, "0.0.0.0")
      .withWebSockets(true)
      .mountService(route, "/")
      .serve
      .compile
      .toList
      .map(_.head)
  }

}


object ServerApp extends Server
