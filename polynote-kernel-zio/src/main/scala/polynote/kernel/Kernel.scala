package polynote.kernel

import java.util.concurrent.TimeUnit

import cats.data.OptionT
import cats.effect.concurrent.Ref
import polynote.kernel.interpreter.{Interpreter, State}
import polynote.messages.{CellID, Notebook}
import zio.clock.Clock
import zio.{Task, TaskR, ZIO}
import zio.interop.catz._

class Kernel private (
  taskManager: TaskManager[KernelEnv],
  scalaCompiler: ScalaCompiler,
  notebookRef: Ref[Task, Notebook],
  interpreterState: Ref[Task, State],
  interpreterFactories: Map[String, Interpreter.Factory],
  interpreters: RefMap[String, Interpreter]
) {

  /**
    * Enqueues a cell to be run with its appropriate interpreter. Evaluating the outer task causes the cell to be
    * queued, and evaluating the inner task blocks until it is finished evaluating.
    */
  def queueCell(id: CellID): TaskR[KernelEnv, TaskR[KernelEnv, Unit]] = taskManager.queue(s"Cell$id", s"Cell $id") {
    for {
      notebook      <- notebookRef.get
      cell          <- ZIO(notebook.cell(id))
      captureOut     = new Deque[Result]()                                                                              // capture outputs into a list for updating the notebook
      cellEnv       <- CellEnvironment.fromKernel(id).map(_.tapResults(captureOut.add))
      interpreter   <- getOrLaunch(cell.language, CellID(0)).provide(cellEnv)
      state         <- interpreterState.get
      prevCells      = notebook.cells.takeWhile(_.id != cell.id)                                                        // find the latest executed state that correlates to a notebook cell
      prevState      = prevCells.reverse.map(_.id).flatMap(state.at).headOption.getOrElse(State.Root)
      clock         <- ZIO.access[Clock](_.clock)                                                                       // time the execution and notify clients of timing
      start         <- clock.currentTime(TimeUnit.MILLISECONDS)
      _             <- cellEnv.publishResult(ExecutionInfo(start, None))
      initialState   = State.id(id, prevState)                                                                          // run the cell while capturing outputs
      resultState   <- interpreter.run(cell.content.toString, initialState)
        .provideSomeM(cellEnv.mkExecutor(scalaCompiler.classLoader))                                                    // provides custom blocking to interpreter run
        .catchAll {
          err => cellEnv.publishResult(ErrorResult(err)).const(initialState)
        }
      end           <- clock.currentTime(TimeUnit.MILLISECONDS)                                                         // finish timing and notify clients of elapsed time
      _             <- cellEnv.publishResult(ExecutionInfo(start, Some(end)))
      _             <- interpreterState.update(_.insertOrReplace(resultState))
      resultList    <- captureOut.toList                                                                                // update the notebook with the accumulated results
      _             <- notebookRef.update(_.setResults(id, resultList))
    } yield ()
  }

  def completionsAt(id: CellID, pos: Int): Task[List[Completion]] = {
      for {
        (cell, interp, state) <- cellInterpreter(id)
        completions           <- OptionT.liftF(interp.completionsAt(cell.content.toString, pos, state))
      } yield completions
    }.value.catchAll(_ => ZIO.succeed(None)).map(_.getOrElse(Nil))

  def parametersAt(id: CellID, pos: Int): Task[Option[Signatures]] = {
      for {
        (cell, interp, state) <- cellInterpreter(id)
        signatures            <- OptionT(interp.parametersAt(cell.content.toString, pos, state))
      } yield signatures
  }.value.catchAll(_ => ZIO.succeed(None))

  def shutdown(): Task[Unit] = taskManager.cancelAll()

  /**
    * Get the cell with the given ID along with its interpreter and state. If its interpreter hasn't been started,
    * the overall result is None.
    */
  private def cellInterpreter(id: CellID) = for {
    notebook    <- OptionT.liftF(notebookRef.get)
    cell        <- OptionT(ZIO.succeed(notebook.getCell(id)).absorb)
    state       <- OptionT.liftF(interpreterState.get)
    prevCells    = notebook.cells.takeWhile(_.id != cell.id)
    prevState    = prevCells.reverse.map(_.id).flatMap(state.at).headOption.getOrElse(State.Root)
    interpreter <- OptionT(interpreters.get(cell.language))
  } yield (cell, interpreter, State.id(id, prevState))

  private def getOrLaunch(language: String, at: CellID): TaskR[CellEnv, Interpreter] =
    interpreters.getOrCreate(language) {
      for {
        factory      <- ZIO.fromOption(interpreterFactories.get(language)).mapError(_ => new IllegalArgumentException(s"No interpreter for $language"))
        env          <- interpreterFactoryEnv()
        interpreter  <- factory().provide(env)
        currentState <- interpreterState.get
        insertStateAt = currentState.rewindWhile(s => s.id != at && s.id >= 0)
        newState     <- interpreter.init(insertStateAt)
        _            <- interpreterState.update(_.insert(insertStateAt.id, newState))
      } yield interpreter
    }

  private def interpreterFactoryEnv(): Task[ScalaCompiler.Provider] = ZIO.succeed {
    new ScalaCompiler.Provider {
      val scalaCompiler: ScalaCompiler = Kernel.this.scalaCompiler
    }
  }

}

object Kernel extends Factory {

  def apply(): TaskR[KernelFactoryEnv, Kernel] = for {
    taskManager <- ZIO.access[TaskManager.Provider[KernelEnv]](_.taskManager)
    compiler    <- ZIO.access[ScalaCompiler.Provider](_.scalaCompiler)
    interpreterFactories <- ZIO.access[Interpreter.Factories](_.interpreterFactories)
    notebookRef <- ZIO.access[CurrentNotebook](_.currentNotebook)
    interpState <- Ref[Task].of[State](State.Root)
    interpreters <- RefMap.empty[String, Interpreter]
  } yield new Kernel(taskManager, compiler, notebookRef, interpState, interpreterFactories, interpreters)

}

trait Factory {
  def apply(): TaskR[KernelFactoryEnv, Kernel]
}