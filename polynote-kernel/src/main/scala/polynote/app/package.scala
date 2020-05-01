package polynote

import java.io.File

import polynote.kernel.{BaseEnv, GlobalEnv, Kernel}
import polynote.kernel.environment.Config
import polynote.kernel.interpreter.Interpreter
import polynote.kernel.logging.Logging
import zio.{Has, URIO, ZIO, ZLayer}

import scala.annotation.tailrec

package object app {
  type Environment = zio.ZEnv with Logging
  type MainArgs = Has[Args]

  object MainArgs {
    def access: URIO[MainArgs, Args] = ZIO.access[MainArgs](_.get)
  }

  case class Args(
    command: String = "server",
    configFile: File = new File("config.yml"),
    watchUI: Boolean = false,
    rest: List[String] = Nil
  )

  object Args {
    def parse(args: List[String]): ZLayer[Any, Throwable, MainArgs] = {
      val (command, remaining) = parseFirst(args)
      ZLayer.fromEffect(
        ZIO.fromEither(
          parseArgsEither(remaining, Args(command = command))
        ))
    }

    @tailrec
    private def parseFirst(args: List[String]): (String, List[String]) = args match {
      case Nil => ("server", Nil)
      case mainClass :: rest if mainClass startsWith "polynote." => parseFirst(rest)
      case flag :: rest if flag startsWith "-" => ("server", flag :: rest)
      case cmd :: rest => (cmd, rest)
    }

    @tailrec
    private def parseArgsEither(args: List[String], current: Args = Args()): Either[Throwable, Args] = args match {
      case Nil => Right(current)
      case ("--config" | "-c") :: filename :: rest => parseArgsEither(rest, current.copy(configFile = new File(filename)))
      case ("--watch" | "-w") :: rest => parseArgsEither(rest, current.copy(watchUI = true))
      case mainClass :: rest if mainClass startsWith "polynote." => parseArgsEither(rest, current) // class name might be arg0 in some circumstances
      case "--config" :: Nil => Left(new IllegalArgumentException("No config path specified. Usage: `--config path/to/config.yml`"))
      case other :: rest => parseArgsEither(rest, current.copy(rest = current.rest :+ other))
    }
  }

  val globalEnv: ZLayer[BaseEnv with MainArgs with Kernel.Factory, Throwable, GlobalEnv] =
    ((ZLayer.identity[BaseEnv] ++ ZLayer.identity[MainArgs] ++ Config.layer) >>> (ZLayer.identity[Config] ++ Interpreter.Factories.load)) ++
      ZLayer.identity[Kernel.Factory]
}
