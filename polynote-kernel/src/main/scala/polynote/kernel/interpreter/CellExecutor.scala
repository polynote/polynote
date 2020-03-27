package polynote.kernel.interpreter

import java.io.{OutputStream, PrintStream}
import java.lang.reflect.InvocationHandler

import polynote.kernel.environment.{CurrentRuntime, CurrentTask, PublishResult, PublishStatus}
import polynote.kernel.util.ResultOutputStream
import polynote.kernel.{BaseEnv, ExecutionStatus, InterpreterEnv, Output, Result, ScalaCompiler, withContextClassLoader}
import polynote.messages.CellID
import polynote.runtime.KernelRuntime
import zio.{RIO, Runtime, Task, UIO, ZIO, ZLayer}
import zio.blocking.Blocking
import zio.internal.{ExecutionMetrics, Executor}

/**
  * A ZIO [[Executor]] which captures standard output from the given task into the given publisher, and sets the context
  * ClassLoader of the task to the given [[ClassLoader]].
  */
class CellExecutor(publishSync: Result => Unit, classLoader: ClassLoader, blockingExecutor: Executor) extends Executor {
  // we have to make sure that the Java console does the same thing as the Scala console, which is thread-local
  CellExecutor.initJavaConsole

  def yieldOpCount: Int = blockingExecutor.yieldOpCount
  def metrics: Option[ExecutionMetrics] = blockingExecutor.metrics
  def submit(runnable: Runnable): Boolean = {
    blockingExecutor.submit {
      new Runnable {
        def run(): Unit = {
          val console = new PrintStream(new ResultOutputStream(publishSync), true)
          withContextClassLoader(classLoader) {
            try {
              Console.withOut(console) {
                runnable.run()
              }
            } finally {
              console.close()
            }
          }
        }
      }
    }
  }
  def here: Boolean = blockingExecutor.here
}

object CellExecutor {

  // Make sure Java's console uses the thread-local mechanism of the Scala console
  // This way it can reset properly but still obey the Console.withOut mechanism
  lazy val initJavaConsole: Unit = {
    // make sure to initialize Console
    val _ = Console.out
    val dynamicOut = new OutputStream {
      override def write(b: Int): Unit = Console.out.write(b)
    }

    System.setOut(new PrintStream(dynamicOut))
  }

  def layer(classLoader: ClassLoader): ZLayer[BaseEnv with InterpreterEnv, Throwable, Blocking] =
    ZLayer.fromEffect {
      ZIO.mapN(PublishResult.access, ZIO.runtime[Any]) {
        (publish, runtime) => ZIO.access[Blocking] {
          hasBlocking =>
            new Blocking.Service {
              override def blockingExecutor: Executor =
                new CellExecutor(
                  result => runtime.unsafeRun(publish.publish1(result)),
                  classLoader,
                  hasBlocking.get.blockingExecutor)
            }
        }
      }.flatten
    }

}