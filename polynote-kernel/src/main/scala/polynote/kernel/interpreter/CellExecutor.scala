package polynote.kernel.interpreter

import java.io.PrintStream

import polynote.kernel.environment.{CurrentRuntime, CurrentTask, PublishResult, PublishStatus}
import polynote.kernel.util.ResultOutputStream
import polynote.kernel.{ExecutionStatus, Output, Result, ScalaCompiler, withContextClassLoader}
import polynote.messages.CellID
import polynote.runtime.KernelRuntime
import zio.{Runtime, TaskR, UIO, ZIO}
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


/**
  * We provide a customized blocking service to the code execution tasks. It captures standard output into the
  * publisher and sets the thread's context classloader.
  */
class CellIO(
  cellID: CellID,
  env: PublishResult with PublishStatus with CurrentTask with Blocking,
  runtime: Runtime[Any],
  classLoader: ClassLoader
) extends Blocking with CurrentRuntime {
  val blocking: Blocking.Service[Any] = new Blocking.Service[Any] {
    val blockingExecutor: UIO[Executor] =
      env.blocking.blockingExecutor.map(new CellExecutor(result => runtime.unsafeRun(env.publishResult.publish1(result)), classLoader, _))
  }

  val currentRuntime: KernelRuntime = new KernelRuntime(
    new KernelRuntime.Display {
      def content(contentType: String, content: String): Unit = runtime.unsafeRunSync(env.publishResult.publish1(Output(contentType, content)))
    },
    (frac, detail) => runtime.unsafeRun(env.currentTask.tryUpdate(_.progress(frac, Option(detail).filter(_.nonEmpty)))),
    posOpt => runtime.unsafeRun(env.publishStatus.publish1(ExecutionStatus(cellID, posOpt.map(boxed => (boxed._1.intValue(), boxed._2.intValue())))))
  )
}

object CellIO {
  def apply(cellID: CellID): TaskR[PublishResult with PublishStatus with CurrentTask with Blocking with ScalaCompiler.Provider, Blocking with CurrentRuntime] =
    for {
      cl <- ZIO.accessM[ScalaCompiler.Provider](_.scalaCompiler.classLoader)
      bs <- apply(cellID, cl)
    } yield bs

  def apply(cellID: CellID, classLoader: ClassLoader): TaskR[PublishResult with PublishStatus with CurrentTask with Blocking, Blocking with CurrentRuntime] =
    for {
      runtime <- ZIO.runtime[Any]
      env     <- ZIO.access[PublishResult with PublishStatus with CurrentTask with Blocking](identity)
    } yield new CellIO(cellID, env, runtime, classLoader)
}
