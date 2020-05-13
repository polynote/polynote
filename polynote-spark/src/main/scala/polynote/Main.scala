package polynote

import java.io.File

import cats.effect.Effect
import polynote.app.Environment
import polynote.config.{KernelIsolation, PolynoteConfig}
import polynote.kernel.{BaseEnv, GlobalEnv, Kernel, LocalKernel, LocalSparkKernel}
import polynote.kernel.environment.{Config, CurrentNotebook}
import polynote.kernel.environment.Env.LayerOps
import polynote.kernel.interpreter.Interpreter
import polynote.kernel.remote.{RemoteKernel, RemoteSparkKernel}
import polynote.messages.NotebookConfig
import polynote.server.{AppEnv, Server}
import polynote.app.{Args, MainArgs, globalEnv}
import polynote.server.auth.IdentityProvider
import polynote.server.repository.NotebookRepository
import polynote.server.repository.fs.FileSystems
import zio.{Has, Task, ULayer, ZIO, ZLayer}

abstract class Main
object Main extends polynote.app.App {
  private implicit val taskEffect: Effect[Task] = zio.interop.catz.taskEffectInstance

  val main: ZIO[AppEnv, Nothing, Int] =
    MainArgs.access.flatMap {
      args => args.command match {
        case "server"  => new Server().main
        case "run"     => NotebookRunner.main
        case "recover" => RecoverLog.main
        case other     => ZIO.dieMessage(s"Unknown command $other (expected server)")
      }
    }.catchAll {
      str => ZIO.effectTotal {
        System.err.println(str)
        System.err.println()
      }.as(1)
    }

  override def main(args: List[String]): ZIO[Environment, Nothing, Int] = main.provideSomeLayer[BaseEnv] {
    Args.parse(args).orDie andThen
      ((Config.layer.orDie ++ kernelFactory ++ FileSystems.live) andThen
        globalEnv.orDie andThen NotebookRepository.live)
  }

  private val kernelFactory: ULayer[Kernel.Factory] = ZLayer.succeed {
    Kernel.Factory.choose {
      for {
        notebook <- CurrentNotebook.get
        config   <- Config.access
      } yield {
        val notebookConfig = notebook.config.getOrElse(NotebookConfig.empty)
        val isSpark = notebookConfig.sparkTemplate.nonEmpty || {
          notebookConfig.sparkConfig match {
            case None                     => false
            case Some(map) if map.isEmpty => false
            case Some(_)                  => true
          }
        }

        config.behavior.kernelIsolation match {
          case KernelIsolation.Always | KernelIsolation.SparkOnly if isSpark => RemoteSparkKernel
          case KernelIsolation.Never                              if isSpark => LocalSparkKernel
          case KernelIsolation.Always                                        => RemoteKernel
          case _                                                             => LocalKernel
        }
      }
    }
  }

}
