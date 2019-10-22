package polynote.kernel.environment

import cats.effect.concurrent.Ref
import fs2.Stream
import fs2.concurrent.SignallingRef
import polynote.config.PolynoteConfig
import polynote.env.ops.Enrich
import polynote.kernel.interpreter.CellExecutor
import polynote.kernel.util.Publish
import polynote.kernel.{CellEnv, ExecutionStatus, InterpreterEnv, InterpreterEnvT, KernelStatusUpdate, Output, Result, TaskInfo}
import polynote.messages.{CellID, Message, Notebook, NotebookCell, NotebookConfig, NotebookUpdate}
import polynote.runtime.KernelRuntime
import zio.blocking.Blocking
import zio.internal.Executor
import zio.interop.catz._
import zio.{Task, RIO, UIO, ZIO}

//////////////////////////////////////////////////////////////////////
// Environment modules to mix for various layers of the application //
//////////////////////////////////////////////////////////////////////

/**
  * The capability to access the current configuration as a [[PolynoteConfig]]
  */
trait Config {
  val polynoteConfig: PolynoteConfig
}

object Config {
  def access: RIO[Config, PolynoteConfig] = ZIO.access[Config](_.polynoteConfig)
  def of(config: PolynoteConfig): Config = new Config {
    val polynoteConfig: PolynoteConfig = config
  }
}

/**
  * The capability to publish status updates
  */
trait PublishStatus {
  val publishStatus: Publish[Task, KernelStatusUpdate]
}

object PublishStatus {
  def access: RIO[PublishStatus, Publish[Task, KernelStatusUpdate]] = ZIO.access[PublishStatus](_.publishStatus)
  def apply(statusUpdate: KernelStatusUpdate): RIO[PublishStatus, Unit] =
    ZIO.accessM[PublishStatus](_.publishStatus.publish1(statusUpdate))
}

/**
  * The capability to publish results
  */
trait PublishResult {
  val publishResult: Publish[Task, Result]
}

object PublishResult {
  def access: RIO[PublishResult, Publish[Task, Result]] = ZIO.access[PublishResult](_.publishResult)
  def apply(result: Result): RIO[PublishResult, Unit] =
    ZIO.accessM[PublishResult](_.publishResult.publish1(result))

  def apply(results: List[Result]): RIO[PublishResult, Unit] =
    access.flatMap {
      pr => Stream.emits(results).through(pr.publish).compile.drain
    }
}

/**
  * The capability to publish general messages
  */
trait PublishMessage {
  val publishMessage: Publish[Task, Message]
}

object PublishMessage {
  def access: RIO[PublishMessage, Publish[Task, Message]] = ZIO.access[PublishMessage](_.publishMessage)
  def apply(message: Message): RIO[PublishMessage, Unit] =
    ZIO.accessM[PublishMessage](_.publishMessage.publish1(message))

  def of(publish: Publish[Task, Message]): PublishMessage = new PublishMessage {
    val publishMessage: Publish[Task, Message] = publish
  }
}

/**
  * The capability to access and update the current task as a [[TaskInfo]]
  */
trait CurrentTask {
  val currentTask: Ref[Task, TaskInfo]
}

object CurrentTask {
  def access: RIO[CurrentTask, Ref[Task, TaskInfo]] = ZIO.access[CurrentTask](_.currentTask)
  def get: RIO[CurrentTask, TaskInfo] = access.flatMap(_.get)

  def update(fn: TaskInfo => TaskInfo): RIO[CurrentTask, Unit] = for {
    ref   <- access
    value <- ref.get
    _     <- if (fn(value) != value) ref.update(fn) else ZIO.unit
  } yield ()

  def of(ref: Ref[Task, TaskInfo]): CurrentTask = new CurrentTask {
    val currentTask: Ref[Task, TaskInfo] = ref
  }
}

/**
  * The capability to access the current [[KernelRuntime]]
  */
trait CurrentRuntime {
  val currentRuntime: KernelRuntime
}

object CurrentRuntime {
  object NoRuntime extends KernelRuntime(
    new KernelRuntime.Display {
      def content(contentType: String, content: String): Unit = ()
    },
    (_, _) => (),
    _ => ()
  )

  object NoCurrentRuntime extends CurrentRuntime {
    val currentRuntime: KernelRuntime = NoRuntime
  }

  def from(
    cellID: CellID,
    publishResult: Publish[Task, Result],
    publishStatus: Publish[Task, KernelStatusUpdate],
    taskRef: Ref[Task, TaskInfo]
  ): Task[CurrentRuntime] = ZIO.runtime.map {
    runtime =>
      new CurrentRuntime {
        val currentRuntime: KernelRuntime = new KernelRuntime(
          new KernelRuntime.Display {
            def content(contentType: String, content: String): Unit = runtime.unsafeRunSync(publishResult.publish1(Output(contentType, content)))
          },
          (frac, detail) => runtime.unsafeRunAsync_(taskRef.tryUpdate(_.progress(frac, Option(detail).filter(_.nonEmpty)))),
          posOpt => runtime.unsafeRunAsync_(publishStatus.publish1(ExecutionStatus(cellID, posOpt.map(boxed => (boxed._1.intValue(), boxed._2.intValue())))))
        )
      }
  }

  def from(cellID: CellID): RIO[PublishResult with PublishStatus with CurrentTask, CurrentRuntime] =
    ((PublishResult.access, PublishStatus.access, CurrentTask.access)).map3(CurrentRuntime.from(cellID, _, _, _)).flatten

  def access: ZIO[CurrentRuntime, Nothing, KernelRuntime] = ZIO.access[CurrentRuntime](_.currentRuntime)
}

/**
  * The capability to access and modify the current [[Notebook]]
  */
// TODO: should separate out a read-only capability for interpreters (they have no business modifying the notebook)
trait CurrentNotebook {
  val currentNotebook: Ref[Task, (Int, Notebook)]
}

object CurrentNotebook {
  def of(ref: Ref[Task, (Int, Notebook)]): CurrentNotebook = new CurrentNotebook {
    val currentNotebook: Ref[Task, (Int, Notebook)] = ref
  }

  def get: RIO[CurrentNotebook, Notebook] = getVersioned.map(_._2)
  def getVersioned: RIO[CurrentNotebook, (Int, Notebook)] = ZIO.accessM[CurrentNotebook](_.currentNotebook.get)

  def getCell(id: CellID): RIO[CurrentNotebook, NotebookCell] = get.flatMap {
    notebook => ZIO.fromOption(notebook.getCell(id)).mapError(_ => new NoSuchElementException(s"No such cell $id in notebook ${notebook.path}"))
  }

  def config: RIO[CurrentNotebook, NotebookConfig] = get.map(_.config.getOrElse(NotebookConfig.empty))
}

/**
  * The capability to access a stream of changes to the notebook's content
  */
trait NotebookUpdates {
  def notebookUpdates: Stream[Task, NotebookUpdate]
}

object NotebookUpdates {
  def access: RIO[NotebookUpdates, Stream[Task, NotebookUpdate]] = ZIO.access[NotebookUpdates](_.notebookUpdates)
}


/////////////////////////////////////////////////////////////////////////////////////////////
// Some concrete environment classes to make it easier to instantiate composed env modules //
/////////////////////////////////////////////////////////////////////////////////////////////
case class InterpreterEnvironment(
  blocking: Blocking.Service[Any],
  publishResult: Publish[Task, Result],
  publishStatus: Publish[Task, KernelStatusUpdate],
  currentTask: Ref[Task, TaskInfo],
  currentRuntime: KernelRuntime
) extends InterpreterEnvT {

  def localBlocking(mk: Executor => Executor): InterpreterEnvironment = copy(
    blocking = new Blocking.Service[Any] {
      override def blockingExecutor: ZIO[Any, Nothing, Executor] = InterpreterEnvironment.this.blocking.blockingExecutor.map(mk)
    }
  )

  def tapResults(to: Result => Task[Unit]): InterpreterEnvironment = copy(
    publishResult = publishResult.tap(to)
  )

  /**
    * Insert custom [[Blocking]] service.
    * @see [[CellExecutor]]
    */
  def mkExecutor(classLoader: Task[ClassLoader]): Task[InterpreterEnvironment] = for {
    runtime     <- ZIO.runtime[Any]
    classLoader <- classLoader
  } yield localBlocking(new CellExecutor(result => runtime.unsafeRun(publishResult.publish1(result)), classLoader, _))

}

object InterpreterEnvironment {
  def from(env: InterpreterEnv): InterpreterEnvironment = env match {
    case env: InterpreterEnvironment => env
    case env => InterpreterEnvironment(env.blocking, env.publishResult, env.publishStatus, env.currentTask, env.currentRuntime)
  }

  def fromKernel(cellID: CellID): RIO[Blocking with CellEnv with CurrentTask, InterpreterEnvironment] = for {
    env            <- ZIO.access[Blocking with CellEnv with CurrentTask](identity)
    currentRuntime <- CurrentRuntime.from(cellID)
  } yield InterpreterEnvironment(env.blocking, env.publishResult, env.publishStatus, env.currentTask, currentRuntime.currentRuntime)

  def noTask(cellID: CellID): RIO[Blocking with CellEnv, InterpreterEnvironment] = for {
    env            <- ZIO.access[Blocking with CellEnv](identity)
    taskRef        <- Ref[Task].of(TaskInfo("None"))
    currentRuntime <- CurrentRuntime.from(cellID, env.publishResult, env.publishStatus, taskRef)
  } yield InterpreterEnvironment(env.blocking, env.publishResult, env.publishStatus, taskRef, currentRuntime.currentRuntime)
}

/**
  * Some utilities for enrichment of environment
  */
object Env {

  def enrichWith[A, B](a: A, b: B)(implicit enrich: Enrich[A, B]): A with B = enrich(a, b)

  def enrich[A]: Enricher[A] = new Enricher()

  class Enricher[A] {
    def apply[B](b: B)(implicit enrich: Enrich[A, B]): ZIO[A, Nothing, A with B] = ZIO.access[A](identity).map(enrichWith[A, B](_, b))
  }

  def enrichM[A]: MEnricher[A] = new MEnricher

  class MEnricher[A]() {
    def apply[R <: A, E, B](ioB: ZIO[R, E, B])(implicit enrich: Enrich[A, B]): ZIO[R, E, A with B] = ZIO.access[A](identity).flatMap {
      a => ioB.map {
        b => enrichWith[A, B](a, b)
      }
    }
  }

}