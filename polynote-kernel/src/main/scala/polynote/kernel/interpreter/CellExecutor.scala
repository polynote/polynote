package polynote.kernel.interpreter

import java.io.PrintStream

import polynote.kernel.environment.{CurrentRuntime, CurrentTask, PublishResult, PublishStatus}
import polynote.kernel.util.ResultOutputStream
import polynote.kernel.{ExecutionStatus, Output, Result, ScalaCompiler, withContextClassLoader}
import polynote.messages.CellID
import polynote.runtime.KernelRuntime
import zio.{Runtime, RIO, UIO, ZIO}
import zio.blocking.Blocking
import zio.internal.{ExecutionMetrics, Executor}

/**
  * A ZIO [[Executor]] which captures standard output from the given task into the given publisher, and sets the context
  * ClassLoader of the task to the given [[ClassLoader]].
  */
class CellExecutor(publishSync: Result => Unit, classLoader: ClassLoader, blockingExecutor: Executor) extends Executor {
  def yieldOpCount: Int = blockingExecutor.yieldOpCount
  def metrics: Option[ExecutionMetrics] = blockingExecutor.metrics
  def submit(runnable: Runnable): Boolean = {
    blockingExecutor.submit {
      new Runnable {
        def run(): Unit = {
          val console = new PrintStream(new ResultOutputStream(publishSync), true)
          withContextClassLoader(classLoader) {
            val oldJavaConsole = System.out
            try {
              System.setOut(console)
              Console.withOut(console) {
                runnable.run()
              }
            } finally {
              System.setOut(oldJavaConsole)
              console.close()
            }
          }
        }
      }
    }
  }
  def here: Boolean = blockingExecutor.here
}