package polynote.server

import polynote.config.KernelIsolation
import polynote.kernel.environment.{Config, CurrentNotebook}
import polynote.kernel.remote.{RemoteKernel, RemoteSparkKernel}
import polynote.kernel.{Kernel, LocalKernel, LocalSparkKernel}
import polynote.messages.NotebookConfig

abstract class ZIOSparkServer
object ZIOSparkServer extends ZIOServer {
  protected val kernelFactory: Kernel.Factory.Service = Kernel.Factory.choose {
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

}
