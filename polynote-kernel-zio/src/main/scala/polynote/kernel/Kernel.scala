package polynote.kernel

import polynote.kernel.environment.CurrentNotebook
import polynote.messages.CellID
import zio.{Task, TaskR, ZIO}

trait Kernel {
  /**
    * Enqueues a cell to be run with its appropriate interpreter. Evaluating the outer task causes the cell to be
    * queued, and evaluating the inner task blocks until it is finished evaluating.
    */
  def queueCell(id: CellID): TaskR[BaseEnv with GlobalEnv with CellEnv, Task[Unit]]

  /**
    * Provide completions for the given position in the given cell
    */
  def completionsAt(id: CellID, pos: Int): TaskR[CurrentNotebook, List[Completion]]

  /**
    * Provide parameter hints for the given position in the given cell
    */
  def parametersAt(id: CellID, pos: Int): TaskR[CurrentNotebook, Option[Signatures]]

  /**
    * Perform any initialization for the kernel
    */
  def init(): TaskR[BaseEnv with GlobalEnv with CellEnv, Unit]

  /**
    * Shut down this kernel and its interpreters, releasing their resources and ending any internally managed tasks or processes
    */
  def shutdown(): Task[Unit]

  /**
    * Provide the current busy status of the kernel
    */
  def status(): Task[KernelBusyState]

  /**
    * Provide all values that currently are known by the kernel
    */
  def values(): Task[List[ResultValue]]
}

object Kernel {
  trait Factory {
    val kernelFactory: Factory.Service
  }

  object Factory {
    trait Service {
      def apply(): TaskR[BaseEnv with GlobalEnv with CellEnv, Kernel]
    }

    def of(factory: Service): Factory = new Factory {
      val kernelFactory: Service = factory
    }

    def access: TaskR[Kernel.Factory, Service] = ZIO.access[Kernel.Factory](_.kernelFactory)
  }
}
