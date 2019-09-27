package polynote.kernel

import polynote.buildinfo.BuildInfo
import polynote.kernel.environment.{CurrentNotebook, NotebookUpdates}
import polynote.messages.{ByteVector32, CellID, HandleType}
import polynote.runtime.{StreamingDataRepr, TableOp}
import zio.{Task, TaskR, ZIO}

trait Kernel {
  /**
    * Enqueues a cell to be run with its appropriate interpreter. Evaluating the outer task causes the cell to be
    * queued, and evaluating the inner task blocks until it is finished evaluating.
    */
  def queueCell(id: CellID): TaskR[BaseEnv with GlobalEnv with CellEnv, Task[Unit]]

  def cancelAll(): TaskR[BaseEnv with TaskManager, Unit] = TaskManager.access.flatMap(_.cancelAll())

  /**
    * Provide completions for the given position in the given cell
    */
  def completionsAt(id: CellID, pos: Int): TaskR[BaseEnv with GlobalEnv with CellEnv, List[Completion]]

  /**
    * Provide parameter hints for the given position in the given cell
    */
  def parametersAt(id: CellID, pos: Int): TaskR[BaseEnv with GlobalEnv with CellEnv, Option[Signatures]]

  /**
    * Perform any initialization for the kernel
    */
  def init(): TaskR[BaseEnv with GlobalEnv with CellEnv, Unit]

  /**
    * Shut down this kernel and its interpreters, releasing their resources and ending any internally managed tasks or processes
    */
  def shutdown(): TaskB[Unit]

  /**
    * Provide the current busy status of the kernel
    */
  def status(): TaskB[KernelBusyState]

  /**
    * Provide free-form key/value HTML information about the kernel
    */
  def info(): TaskG[KernelInfo] = ZIO.succeed(KernelInfo(
    "Polynote Version:" -> s"""<span id="version">${BuildInfo.version}</span>""",
    "Build Commit:"     -> s"""<span id="commit">${BuildInfo.commit}</span>"""
  ))

  /**
    * Provide all values that currently are known by the kernel
    */
  def values(): TaskB[List[ResultValue]]

  /**
    * @return An array of up to `count` [[scodec.bits.ByteVector]] elements, in which each element represents one encoded
    *         element from the given handle of the given type
    */
  def getHandleData(handleType: HandleType, handle: Int, count: Int): TaskR[BaseEnv with StreamingHandles, Array[ByteVector32]]

  /**
    * Create a new [[StreamingDataRepr]] handle by performing [[TableOp]] operations on the given streaming handle. The
    * given handle itself must be unaffected.
    *
    * @return If the operations make no changes, returns the given handle. If the operations are valid for the stream,
    *         and it supports the modification, returns a new handle for the modified stream. If the stream doesn't support
    *         modification, returns None. If the modifications are invalid or unsupported by the the stream, it may either
    *         raise an error or return None.
    */
  def modifyStream(handleId: Int, ops: List[TableOp]): TaskR[BaseEnv with StreamingHandles, Option[StreamingDataRepr]]

  /**
    * Release a handle. No further data will be available using [[getHandleData()]].
    */
  def releaseHandle(handleType: HandleType, handleId: Int): TaskR[BaseEnv with StreamingHandles, Unit]
}

object Kernel {
  trait Factory {
    val kernelFactory: Factory.Service
  }

  object Factory {
    trait Service {
      def apply(): TaskR[BaseEnv with GlobalEnv with CellEnv with NotebookUpdates, Kernel]
    }

    def of(factory: Service): Factory = new Factory {
      val kernelFactory: Service = factory
    }

    def choose(choose: TaskR[BaseEnv with GlobalEnv with CellEnv, Service]): Service = new Service {
      override def apply(): TaskR[BaseEnv with GlobalEnv with CellEnv with NotebookUpdates, Kernel] = choose.flatMap(_.apply())
    }

    def access: TaskR[Kernel.Factory, Service] = ZIO.access[Kernel.Factory](_.kernelFactory)
  }

  case object InterpreterNotStarted extends Throwable
}
