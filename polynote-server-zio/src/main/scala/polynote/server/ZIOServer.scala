package polynote.server

import java.io.File

import org.http4s.dsl.Http4sDsl
import polynote.config.PolynoteConfig
import zio.{App, Task, ZIO}
import zio.interop.catz._

import scala.annotation.tailrec

trait ZIOServer extends App with Http4sDsl[Task] {

  override def run(args: List[String]): ZIO[Environment, Nothing, Int] = for {
    args   <- ZIO.fromEither(ZIOServer.parseArgs(args))
    config <- PolynoteConfig.load[Task](args.configFile).orDie
    port    = config.listen.port
    address = config.listen.host
    host    = if (address == "0.0.0.0") java.net.InetAddress.getLocalHost.getHostAddress else address
    url     = s"http://$host:$port"
  } yield ???

}

object ZIOServer {
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
