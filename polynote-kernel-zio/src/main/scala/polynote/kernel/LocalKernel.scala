package polynote.kernel

import java.util.concurrent.TimeUnit

import cats.effect.concurrent.Ref
import cats.syntax.traverse._
import cats.instances.list._
import fs2.concurrent.SignallingRef
import polynote.kernel.dependency.ZIOCoursierFetcher
import polynote.kernel.environment.{CurrentNotebook, CurrentRuntime, CurrentTask, Env, InterpreterEnvironment, PublishResult, PublishStatus}
import polynote.kernel.interpreter.State.Root
import polynote.kernel.interpreter.{Interpreter, State}
import polynote.kernel.interpreter.scal.ScalaInterpreter
import polynote.kernel.util.RefMap
import polynote.messages.{CellID, truncateTinyString}
import polynote.runtime.{ReprsOf, StringRepr}
import zio.{Task, TaskR, ZIO}
import zio.blocking.Blocking
import zio.clock.Clock
import zio.interop.catz._


class LocalKernel private[kernel] (
  compilerProvider: ScalaCompiler.Provider,
  interpreterState: Ref[Task, State],
  interpreters: RefMap[String, Interpreter],
  busyState: SignallingRef[Task, KernelBusyState]
) extends Kernel {

  import compilerProvider.scalaCompiler

  override def queueCell(id: CellID): TaskR[BaseEnv with GlobalEnv with CellEnv, Task[Unit]] =
    TaskManager.queue(s"Cell$id", s"Cell $id", errorWith = TaskStatus.Complete) {
      val captureOut   = new Deque[Result]()
      val saveResults  = captureOut.toList >>= (CurrentNotebook.setResults(id, _))
      val run = for {
        _             <- busyState.update(_.setBusy)
        notebookRef   <- CurrentNotebook.access
        notebook      <- notebookRef.get
        cell          <- ZIO(notebook.cell(id))
        interpEnv     <- InterpreterEnvironment.fromKernel(id).map(_.tapResults(captureOut.add))
        interpreter   <- getOrLaunch(cell.language, CellID(0)).provideSomeM(Env.enrich[GlobalEnv with CellEnv](interpEnv: InterpreterEnv))
        state         <- interpreterState.get
        prevCells      = notebook.cells.takeWhile(_.id != cell.id)                                                    // find the latest executed state that correlates to a notebook cell
        prevState      = prevCells.reverse.map(_.id).flatMap(state.at).headOption.getOrElse(state.rewindWhile(s => !(s.prev eq Root)))
        _             <- PublishResult(ClearResults())
        clock         <- ZIO.access[Clock](_.clock)                                                                   // time the execution and notify clients of timing
        start         <- clock.currentTime(TimeUnit.MILLISECONDS)
        _             <- PublishResult(ExecutionInfo(start, None)).provide(interpEnv)
        initialState   = State.id(id, prevState)                                                                      // run the cell while capturing outputs
        resultState   <- (interpreter.run(cell.content.toString, initialState) >>= updateValues)
          .onTermination(_ => CurrentRuntime.access.flatMap(rt => ZIO.effectTotal(rt.clearExecutionStatus())))
          .provideSomeM(interpEnv.mkExecutor(scalaCompiler.classLoader))
        end           <- clock.currentTime(TimeUnit.MILLISECONDS)                                                     // finish timing and notify clients of elapsed time
        _             <- PublishResult(ExecutionInfo(start, Some(end))).provide(interpEnv)
        _             <- updateState(resultState) //interpreterState.update(_.insertOrReplace(resultState))
        _             <- resultState.values.map(PublishResult.apply).sequence.unit                                    // publish the result values
        _             <- saveResults
        _             <- busyState.update(_.setIdle)
      } yield ()

      run.supervised.onInterrupt {
        PublishResult(ErrorResult(new InterruptedException("Execution was interrupted by the user"))).orDie *>
        saveResults.ignore *>
        busyState.update(_.setIdle).orDie
      }.catchAll {
        err =>
          PublishResult(ErrorResult(err))
      }
    }

  private def updateState(resultState: State) = {
    interpreterState.update {
      state =>
        val rs = resultState
        val updated = state.insertOrReplace(resultState)
        updated
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
      _             <- busyState.update(_.setIdle)
    } yield ()
  }

  private def initScala(): TaskR[BaseEnv with GlobalEnv with CellEnv with CurrentTask, State] = for {
    scalaInterp   <- interpreters.get("scala").orDie.get.mapError(_ => new IllegalStateException("No scala interpreter"))
    initialState  <- interpreterState.get
    predefEnv     <- InterpreterEnvironment.fromKernel(initialState.id)
    predefState   <- scalaInterp.init(initialState).provideSomeM(Env.enrich[BaseEnv with GlobalEnv](predefEnv: InterpreterEnv))
    _             <- interpreterState.set(initialState)
  } yield predefState

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
        insertStateAt = currentState.rewindWhile(s => s.id != at && !(s eq Root))
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

object LocalKernel extends Kernel.Factory.Service {

  def apply(): TaskR[BaseEnv with GlobalEnv with CellEnv, Kernel] = for {
    scalaDeps    <- ZIOCoursierFetcher.fetch("scala")
    compiler     <- ScalaCompiler.provider(scalaDeps.map(_._2))
    busyState    <- SignallingRef[Task, KernelBusyState](KernelBusyState(busy = true, alive = true))
    interpreters <- RefMap.empty[String, Interpreter]
    _            <- interpreters.getOrCreate("scala")(ScalaInterpreter().provide(compiler))                        // ensure Scala interpreter is started
    interpState  <- Ref[Task].of[State](State.id(-1))
  } yield new LocalKernel(compiler, interpState, interpreters, busyState)

}