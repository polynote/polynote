package polynote.testing.kernel

import polynote.config.PolynoteConfig
import polynote.kernel.Kernel.Factory
import polynote.kernel.environment.{CurrentNotebook, CurrentRuntime, TaskRef}
import polynote.kernel.interpreter.{CellExecutor, Interpreter, InterpreterState}
import polynote.kernel.logging.Logging
import polynote.kernel.task.TaskManager
import polynote.kernel.util.UPublish
import polynote.kernel.{BaseEnv, CellEnv, GlobalEnv, InterpreterEnv, KernelStatusUpdate, NotebookRef, Result, StreamingHandles, TaskInfo}
import polynote.messages._
import polynote.runtime.KernelRuntime
import polynote.testing.MockPublish
import zio.blocking.Blocking
import zio.{Has, RIO, RefM, Runtime, URIO, ZIO, ZLayer}

case class MockEnv(
  baseEnv: BaseEnv,
  cellID: CellID,
  currentTask: TaskRef,
  publishResult: MockPublish[Result],
  publishStatus: MockPublish[KernelStatusUpdate],
  runtime: Runtime[Any]
) {
  val currentRuntime: KernelRuntime = runtime.unsafeRun(CurrentRuntime.from(cellID, publishResult, publishStatus, currentTask))
  val logging: Logging.Service = new Logging.Service.Default(System.err, baseEnv.get[Blocking.Service])
  val baseLayer: ZLayer[Any, Nothing, BaseEnv with InterpreterEnv] =
    ZLayer.succeedMany(baseEnv) ++
      ZLayer.succeed(logging) ++
      ZLayer.succeed(currentRuntime) ++
      ZLayer.succeed(publishResult: UPublish[Result]) ++
      ZLayer.succeed(publishStatus: UPublish[KernelStatusUpdate]) ++
      ZLayer.succeed(currentTask)

  def toCellEnv(classLoader: ClassLoader): ZLayer[Any, Throwable, BaseEnv with InterpreterEnv] = baseLayer ++ (baseLayer >>> CellExecutor.layer(classLoader))
}

object MockEnv {

  def init: ZLayer[BaseEnv, Nothing, BaseEnv with InterpreterEnv] = ZLayer.fromManagedMany(MockEnv(-1).toManaged_.flatMap(_.baseLayer.build))

  def apply(cellID: Int): URIO[BaseEnv, MockEnv] = for {
    env <- ZIO.access[BaseEnv](identity)
    runtime <- ZIO.runtime[Any]
    currentTask <- RefM.make(TaskInfo(s"Cell$cellID"))
  } yield new MockEnv(env, CellID(cellID), currentTask, new MockPublish, new MockPublish, runtime)

  def layer(cellID: Int): ZLayer[BaseEnv, Nothing, BaseEnv with InterpreterEnv] = ZLayer.fromManagedMany(MockEnv(cellID).toManaged_.flatMap(_.baseLayer.build))

  type Env = BaseEnv with GlobalEnv with CellEnv with StreamingHandles
}

case class MockKernelEnv(
  baseEnv: BaseEnv,
  kernelFactory: Factory.Service,
  publishResult: MockPublish[Result],
  publishStatus: MockPublish[KernelStatusUpdate],
  interpreterFactories: Map[String, List[Interpreter.Factory]],
  taskManager: TaskManager.Service,
  currentNotebook: MockNotebookRef,
  streamingHandles: StreamingHandles.Service,
  sessionID: Int = 0,
  polynoteConfig: PolynoteConfig = PolynoteConfig()
) {

  val logging: Logging.Service = new Logging.Service.Default(System.err, baseEnv.get[Blocking.Service])

  val baseLayer: ZLayer[Any, Nothing, MockEnv.Env] =
    ZLayer.succeedMany {
      baseEnv ++ Has.allOf(kernelFactory, interpreterFactories, taskManager, polynoteConfig) ++
        Has(streamingHandles) ++ Has(publishResult: UPublish[Result]) ++ Has(publishStatus: UPublish[KernelStatusUpdate])
    } ++ CurrentNotebook.layer(currentNotebook) ++ InterpreterState.emptyLayer

}

object MockKernelEnv {
  def apply(kernelFactory: Factory.Service, config: PolynoteConfig, sessionId: Int): RIO[BaseEnv, MockKernelEnv] = for {
    baseEnv         <- ZIO.access[BaseEnv](identity)
    currentNotebook <- MockNotebookRef(Notebook("empty", ShortList.Nil, None))
    publishUpdates   = new MockPublish[KernelStatusUpdate]
    taskManager     <- TaskManager(publishUpdates)
    handles         <- StreamingHandles.make(sessionId)
  } yield new MockKernelEnv(baseEnv, kernelFactory, new MockPublish, publishUpdates, Map.empty, taskManager, currentNotebook, handles, handles.sessionID, config)

  def apply(kernelFactory: Factory.Service, sessionId: Int): RIO[BaseEnv, MockKernelEnv] = apply(kernelFactory, PolynoteConfig(), sessionId)
  def apply(kernelFactory: Factory.Service, config: PolynoteConfig): RIO[BaseEnv, MockKernelEnv] = apply(kernelFactory, config, 0)
  def apply(kernelFactory: Factory.Service): RIO[BaseEnv, MockKernelEnv] = apply(kernelFactory, 0)
}