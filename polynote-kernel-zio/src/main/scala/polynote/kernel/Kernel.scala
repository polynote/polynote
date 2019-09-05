package polynote.kernel

import java.util.concurrent.TimeUnit

import cats.effect.concurrent.Ref
import cats.syntax.traverse._
import cats.instances.list._
import fs2.concurrent.SignallingRef
import polynote.kernel.dependency.ZIOCoursierFetcher
import polynote.kernel.environment.{CurrentNotebook, CurrentTask, Env, InterpreterEnvironment, PublishResult, PublishStatus}
import polynote.kernel.interpreter.scal.ScalaInterpreter
import polynote.kernel.interpreter.{CellIO, Interpreter, State}
import polynote.kernel.util.RefMap
import polynote.messages.{CellID, Notebook, NotebookCell, truncateTinyString}
import polynote.runtime.{ReprsOf, StringRepr}
import polynote.env.ops._
import zio.blocking.Blocking
import zio.clock.Clock
import zio.{Task, TaskR, ZIO}
import zio.interop.catz._

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
    def apply(): TaskR[BaseEnv with GlobalEnv with KernelEnv, Kernel]
  }
}

class LocalKernel private (
  compilerProvider: ScalaCompiler.Provider,
  interpreterState: Ref[Task, State],
  interpreters: RefMap[String, Interpreter],
  busyState: SignallingRef[Task, KernelBusyState]
) extends Kernel {

  import compilerProvider.scalaCompiler

  override def queueCell(id: CellID): TaskR[BaseEnv with GlobalEnv with CellEnv, Task[Unit]] =
    TaskManager.queue(s"Cell$id", s"Cell $id") {
      {
        for {
          _             <- busyState.update(_.setBusy)
          notebookRef   <- CurrentNotebook.access
          notebook      <- notebookRef.get
          cell          <- ZIO(notebook.cell(id))
          captureOut     = new Deque[Result]()                                                                          // capture outputs into a list for updating the notebook
          interpEnv     <- InterpreterEnvironment.fromKernel(id).map(_.tapResults(captureOut.add))
          interpreter   <- getOrLaunch(cell.language, CellID(0)).provideSomeM(Env.enrich[GlobalEnv with CellEnv](interpEnv: InterpreterEnv))
          state         <- interpreterState.get
          prevCells      = notebook.cells.takeWhile(_.id != cell.id)                                                    // find the latest executed state that correlates to a notebook cell
          prevState      = prevCells.reverse.map(_.id).flatMap(state.at).headOption.getOrElse(State.Root)
          _             <- PublishResult(ClearResults())
          clock         <- ZIO.access[Clock](_.clock)                                                                   // time the execution and notify clients of timing
          start         <- clock.currentTime(TimeUnit.MILLISECONDS)
          _             <- PublishResult(ExecutionInfo(start, None)).provide(interpEnv)
          initialState   = State.id(id, prevState)                                                                      // run the cell while capturing outputs
          resultState   <- (interpreter.run(cell.content.toString, initialState) >>= updateValues)
            .provideSomeM(interpEnv.mkExecutor(scalaCompiler.classLoader))                                                // provides custom blocking to interpreter run
            .catchAll {
              err => PublishResult(ErrorResult(err)).provide(interpEnv).const(initialState)
            }
          end           <- clock.currentTime(TimeUnit.MILLISECONDS)                                                     // finish timing and notify clients of elapsed time
          _             <- PublishResult(ExecutionInfo(start, Some(end))).provide(interpEnv)
          _             <- interpreterState.update(_.insertOrReplace(resultState))
          _             <- resultState.values.map(PublishResult.apply).sequence.unit                                    // publish the result values
          outputList    <- captureOut.toList                                                                            // update the notebook with the accumulated results
          _             <- notebookRef.update(_.setResults(id, outputList ++ resultState.values))
          _             <- busyState.update(_.setIdle)
        } yield ()
      }.catchAll {
        err => PublishResult(ErrorResult(err))
      }
    }

  override def completionsAt(id: CellID, pos: Int): TaskR[CurrentNotebook, List[Completion]] = {
    for {
      (cell, interp, state) <- cellInterpreter(id)
      completions           <- interp.completionsAt(cell.content.toString, pos, state).mapError(_ => ())
    } yield completions
  }.option.map(_.getOrElse(Nil))

  override def parametersAt(id: CellID, pos: Int): TaskR[CurrentNotebook, Option[Signatures]] = {
    for {
      (cell, interp, state) <- cellInterpreter(id)
      signatures            <- interp.parametersAt(cell.content.toString, pos, state).mapError(_ => ())
    } yield signatures
  }.catchAll(_ => ZIO.succeed(None))

  override def init(): TaskR[BaseEnv with GlobalEnv with CellEnv, Unit] = TaskManager.run("Predef", "Predef") {
    for {
      publishStatus <- PublishStatus.access
      busyUpdater   <- busyState.discrete.terminateAfter(!_.alive).through(publishStatus.publish).compile.drain.fork
      initialState  <- initScala()
      _             <- initialState.values.map(PublishResult.apply).sequence
    } yield ()
  }

  private def initScala(): TaskR[BaseEnv with GlobalEnv with CellEnv with CurrentTask, State] = for {
    scalaInterp   <- interpreters.get("scala").orDie.get.mapError(_ => new IllegalStateException("No scala interpreter"))
    predefEnv     <- InterpreterEnvironment.fromKernel(CellID(-1))
    initialState  <- scalaInterp.init(State.id(-1)).provideSomeM(Env.enrich[BaseEnv with GlobalEnv](predefEnv: InterpreterEnv))
    _             <- interpreterState.set(initialState)
  } yield initialState

  override def shutdown(): Task[Unit] = for {
    _            <- busyState.update(_.setBusy)
    interpreters <- interpreters.values
    _            <- interpreters.map(_.shutdown()).sequence.unit
    _            <- busyState.set(KernelBusyState(busy = false, alive = false))
  } yield ()

  override def status(): Task[KernelBusyState] = busyState.get

  override def values(): Task[List[ResultValue]] = interpreterState.get.map(_.scope)

  /**
    * Get the cell with the given ID along with its interpreter and state. If its interpreter hasn't been started,
    * the overall result is None.
    */
  private def cellInterpreter(id: CellID) = for {
    notebook    <- CurrentNotebook.get.orDie
    cell        <- ZIO.fromOption(notebook.getCell(id))
    state       <- interpreterState.get.orDie
    prevCells    = notebook.cells.takeWhile(_.id != cell.id)
    prevState    = prevCells.reverse.map(_.id).flatMap(state.at).headOption.getOrElse(State.Root)
    interpreter <- interpreters.get(cell.language).orDie.get
  } yield (cell, interpreter, State.id(id, prevState))

  private def getOrLaunch(language: String, at: CellID): TaskR[InterpreterEnv with CurrentNotebook with TaskManager with Interpreter.Factories, Interpreter] =
    interpreters.getOrCreate(language) {
      for {
        factory      <- Interpreter.factory(language)
        interpreter  <- factory().provideSomeM(Env.enrich[CurrentNotebook with TaskManager](compilerProvider))
        currentState <- interpreterState.get
        insertStateAt = currentState.rewindWhile(s => s.id != at && s.id >= 0)
        newState     <- interpreter.init(insertStateAt)
        _            <- interpreterState.update(_.insert(insertStateAt.id, newState))
      } yield interpreter
    }

  /**
    * Finds reprs of each value in the state, and returns a new state with the values updated to include the reprs
    */
  private def updateValues(state: State): TaskR[Blocking, State] = {
    import scalaCompiler.global, global.{appliedType, typeOf}
    val (names, types) = state.values.map {v =>
      v.name -> appliedType(typeOf[ReprsOf[Any]].typeConstructor, v.scalaType.asInstanceOf[global.Type])
    }.toMap.toList.unzip
    scalaCompiler.inferImplicits(types).map {
      instances =>
        val instanceMap = names.zip(instances).collect {
          case (name, Some(instance)) => name -> instance.asInstanceOf[ReprsOf[Any]]
        }.toMap

        def updateValue(value: ResultValue): ResultValue = {
          val reprs = instanceMap.get(value.name).toList.flatMap(_.apply(value.value).toList) match {
            case reprs if reprs.exists(_.isInstanceOf[StringRepr]) => reprs
            case reprs => reprs :+ StringRepr(truncateTinyString(Option(value.value).map(_.toString).getOrElse("null")))
          }
          value.copy(reprs = reprs)
        }

        state.updateValues(updateValue)
    }

  }
}

object LocalKernel extends Kernel.Factory {

  def apply(): TaskR[BaseEnv with GlobalEnv with KernelEnv, Kernel] = for {
    scalaDeps    <- ZIOCoursierFetcher.fetch("scala")
    compiler     <- ScalaCompiler.provider(scalaDeps)
    busyState    <- SignallingRef[Task, KernelBusyState](KernelBusyState(busy = true, alive = true))
    interpreters <- RefMap.empty[String, Interpreter]
    _            <- interpreters.getOrCreate("scala")(ScalaInterpreter().provide(compiler))                        // ensure Scala interpreter is started
    interpState  <- Ref[Task].of[State](State.Root)
  } yield new LocalKernel(compiler, interpState, interpreters, busyState)

}