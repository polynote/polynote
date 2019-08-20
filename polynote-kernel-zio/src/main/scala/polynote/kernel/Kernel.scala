package polynote.kernel

import java.util.concurrent.TimeUnit

import cats.data.OptionT
import cats.effect.concurrent.Ref
import fs2.Stream
import polynote.kernel.interpreter.{Interpreter, InterpreterEnv, PublishResults, State}
import polynote.kernel.util.Publish
import polynote.messages.{CellID, Notebook}
import polynote.runtime.KernelRuntime
import zio.blocking.Blocking
import zio.clock.Clock
import zio.{Task, TaskR, ZIO}
import zio.interop.catz._

class Kernel private (
  taskManager: TaskManager,
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
  def queueCell(id: CellID): TaskR[Blocking with Clock with Broadcasts with PublishResults, Task[Unit]] = {
    val run = for {
      notebook      <- notebookRef.get
      cell          <- ZIO(notebook.cell(id))
      interpreter   <- getOrLaunch(cell.language, CellID(0))
      state         <- interpreterState.get
      publishResult <- ZIO.access[PublishResults](_.publishResult)

      // find the latest executed state that correlates to a notebook cell
      prevCells      = notebook.cells.takeWhile(_.id != cell.id)
      prevState      = prevCells.reverse.map(_.id).flatMap(state.at).headOption.getOrElse(State.Root)

      // time the execution and notify clients of timing
      clock         <- ZIO.access[Clock](_.clock)
      start         <- clock.currentTime(TimeUnit.MILLISECONDS)

      // capture outputs into a list for updating the notebook
      _             <- publishResult(ExecutionInfo(start, None))
      captureOut     = new Deque[Result]()
      captureAndPublish = (r: Result) => publishResult(r) *> captureOut.add(r)

      // run the cell while capturing outputs
      initialState   = State.id(id, prevState)
      resultState   <- InterpreterEnv.withPublish(captureAndPublish) {
        interpreter.run(cell.content.toString, initialState).catchAll {
          err => captureAndPublish(ErrorResult(err)).const(initialState)
        }
      }

      // finish timing
      end           <- clock.currentTime(TimeUnit.MILLISECONDS)
      _             <- publishResult(ExecutionInfo(start, Some(end)))

      // update the state
      _             <- interpreterState.update(_.insertOrReplace(resultState))

      // update the notebook with the accumulated results
      resultList    <- captureOut.toList
      _             <- notebookRef.update(_.setResults(id, resultList))
    } yield ()

    ZIO.access[Blocking with Broadcasts with PublishResults with Clock](identity).flatMap {
      outerEnv => taskManager.queue(s"Cell$id", s"Cell $id") {
        withInterpreterEnv(id, outerEnv, outerEnv.publishResult)(run)
      }
    }
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

  private def getOrLaunch(language: String, at: CellID): TaskR[Blocking with Broadcasts with PublishResults with CurrentTask with CurrentRuntime, Interpreter] =
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

  /**
    * Provide the interpreter environment to the given cell
    */
  private def withInterpreterEnv[A](
    cellID: CellID,
    outerEnv: Blocking with Clock with Broadcasts,
    publishResult: Result => Task[Unit])(
    task: TaskR[Blocking with Broadcasts with Clock with PublishResults with CurrentTask with CurrentRuntime with CurrentCell, A]
  ): TaskR[CurrentTask, A] = ZIO.runtime.flatMap {
    runtime =>
      task.provideSome[CurrentTask] {
        innerEnv => new InterpreterEnv(outerEnv, cellID, publishResult, innerEnv.currentTask, runtime)
      }
  }

  private def interpreterFactoryEnv(): Task[ScalaCompiler.Provider] = ZIO.succeed {
    new ScalaCompiler.Provider {
      val scalaCompiler: ScalaCompiler = Kernel.this.scalaCompiler
    }
  }

}

object Kernel {

  def apply(
    interpreterFactories: Map[String, Interpreter.Factory]
  ): TaskR[TaskManager.Provider with ScalaCompiler.Provider with Interpreter.Factories with CurrentNotebook, Kernel] = for {
    taskManager <- ZIO.access[TaskManager.Provider](_.taskManager)
    compiler    <- ZIO.access[ScalaCompiler.Provider](_.scalaCompiler)
    interpreterFactories <- ZIO.access[Interpreter.Factories](_.interpreterFactories)
    notebookRef <- ZIO.access[CurrentNotebook](_.currentNotebook)
    interpState <- Ref[Task].of[State](State.Root)
    interpreters <- RefMap.empty[String, Interpreter]
  } yield new Kernel(taskManager, compiler, notebookRef, interpState, interpreterFactories, interpreters)

}