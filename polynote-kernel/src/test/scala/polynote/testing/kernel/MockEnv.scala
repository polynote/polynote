package polynote.testing.kernel

import cats.effect.concurrent.Ref
import fs2.Stream
import fs2.concurrent.{Queue, SignallingRef, Topic}
import polynote.config.PolynoteConfig
import polynote.kernel.Kernel.Factory
import polynote.kernel.environment.{CurrentRuntime, InterpreterEnvironment, NotebookUpdates}
import polynote.kernel.interpreter.Interpreter
import polynote.kernel.logging.Logging
import polynote.kernel.{BaseEnv, BaseEnvT, CellEnvT, GlobalEnvT, InterpreterEnvT, KernelStatusUpdate, Result, StreamingHandles, TaskInfo, TaskManager}
import polynote.messages._
import polynote.runtime.{KernelRuntime, StreamingDataRepr, TableOp}
import polynote.testing.MockPublish
import zio.blocking.Blocking
import zio.clock.Clock
import zio.interop.catz._
import zio.{Runtime, Task, TaskR, ZIO}

case class MockEnv(
  baseEnv: BaseEnv,
  cellID: CellID,
  currentTask: SignallingRef[Task, TaskInfo],
  publishResult: MockPublish[Result],
  publishStatus: MockPublish[KernelStatusUpdate],
  runtime: Runtime[Any]
) extends BaseEnvT with InterpreterEnvT {
  val clock: Clock.Service[Any] = baseEnv.clock
  val blocking: Blocking.Service[Any] = baseEnv.blocking
  val system: zio.system.System.Service[Any] = baseEnv.system
  val currentRuntime: KernelRuntime = runtime.unsafeRun(CurrentRuntime.from(cellID, publishResult, publishStatus, currentTask)).currentRuntime
  override val logging: Logging.Service = new Logging.Service.Default(System.err, blocking)
  def toCellEnv(classLoader: ClassLoader): InterpreterEnvironment = runtime.unsafeRun(InterpreterEnvironment.from(this).mkExecutor(ZIO.succeed(classLoader)))
}

object MockEnv {
  def apply(cellID: Int): TaskR[BaseEnv, MockEnv] = for {
    env <- ZIO.access[BaseEnv](identity)
    runtime <- ZIO.runtime[Any]
    currentTask <- SignallingRef[Task, TaskInfo](TaskInfo(s"Cell$cellID"))
  } yield new MockEnv(env, CellID(cellID), currentTask, new MockPublish, new MockPublish, runtime)
}

case class MockKernelEnv(
  baseEnv: BaseEnv,
  kernelFactory: Factory.Service,
  publishResult: MockPublish[Result],
  publishStatus: MockPublish[KernelStatusUpdate],
  interpreterFactories: Map[String, List[Interpreter.Factory]],
  taskManager: TaskManager.Service,
  updateTopic: Topic[Task, Option[NotebookUpdate]],
  currentNotebook: SignallingRef[Task, Notebook],
  streamingHandles: StreamingHandles.Service,
  sessionID: Int = 0
) extends BaseEnvT with GlobalEnvT with CellEnvT with StreamingHandles with NotebookUpdates {
  val clock: Clock.Service[Any] = baseEnv.clock
  val blocking: Blocking.Service[Any] = baseEnv.blocking
  val system: zio.system.System.Service[Any] = baseEnv.system
  val logging: Logging.Service = new Logging.Service.Default(System.err, blocking)
  val polynoteConfig: PolynoteConfig = PolynoteConfig()
  val notebookUpdates: Stream[Task, NotebookUpdate] = updateTopic.subscribe(128).unNone
}

object MockKernelEnv {
  def apply(kernelFactory: Factory.Service): TaskR[BaseEnv, MockKernelEnv] = for {
    baseEnv         <- ZIO.access[BaseEnv](identity)
    currentNotebook <- SignallingRef[Task, Notebook](Notebook("empty", ShortList(Nil), None))
    updateTopic     <- Topic[Task, Option[NotebookUpdate]](None)
    publishUpdates   = new MockPublish[KernelStatusUpdate]
    taskManager     <- TaskManager(publishUpdates)
    handles         <- StreamingHandles.make(0)
  } yield new MockKernelEnv(baseEnv, kernelFactory, new MockPublish, publishUpdates, Map.empty, taskManager, updateTopic, currentNotebook, handles.streamingHandles, handles.sessionID)
}