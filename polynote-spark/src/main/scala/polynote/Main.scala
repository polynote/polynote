package polynote

import polynote.config.KernelIsolation
import polynote.kernel.{Kernel, LocalKernel, LocalSparkKernel}
import polynote.kernel.environment.{Config, CurrentNotebook}
import polynote.kernel.remote.{RemoteKernel, RemoteSparkKernel}
import polynote.messages.NotebookConfig
import polynote.server.Server

abstract class Main
object Main {
  def main(args: Array[String]): Unit = args.headOption match {
    case None                            => runServer(args)
    case Some(arg) if arg startsWith "-" => runServer(args)
    case Some("server")                  => runServer(args.tail)
    // TODO: headless run notebook, repl, etc
    case Some(other) => throw new IllegalArgumentException(s"Unknown command $other")
  }

  private def runServer(args: Array[String]): Unit = {
    val kernelFactory = Kernel.Factory.choose {
      for {
        notebook <- CurrentNotebook.get
        config   <- Config.access
      } yield {
        val isSpark = notebook.config.getOrElse(NotebookConfig.empty).sparkConfig match {
          case None                     => false
          case Some(map) if map.isEmpty => false
          case Some(_)                  => true
        }

        config.behavior.kernelIsolation match {
          case KernelIsolation.Always | KernelIsolation.SparkOnly if isSpark => RemoteSparkKernel
          case KernelIsolation.Never                              if isSpark => LocalSparkKernel
          case KernelIsolation.Always                                        => RemoteKernel
          case _                                                             => LocalKernel
        }
      }
    }
    new Server(kernelFactory).main(args)
  }
}
