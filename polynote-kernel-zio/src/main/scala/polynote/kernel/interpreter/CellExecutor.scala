package polynote.kernel.interpreter

import java.io.PrintStream

import polynote.kernel.{Result, ResultOutputStream, withContextClassLoader}
import zio.{UIO, ZIO}
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
          val console = new PrintStream(new ResultOutputStream(publishSync))
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


/**
  * We provide a customized blocking service to the code execution tasks. It captures standard output into the
  * publisher and sets the thread's context classloader.
  */
class BlockingService(publishSync: Result => Unit, publishAsync: Result => Unit, classLoader: ClassLoader, parentExec: Executor) extends Blocking {
  val blocking: Blocking.Service[Any] = new Blocking.Service[Any] {
    val blockingExecutor: UIO[Executor] = ZIO.succeed(new CellExecutor(publishSync, classLoader, parentExec))
  }
}
