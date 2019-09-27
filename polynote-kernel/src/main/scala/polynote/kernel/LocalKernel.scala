package polynote.kernel

import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import cats.effect.concurrent.Ref
import cats.syntax.traverse._
import cats.instances.list._
import fs2.concurrent.SignallingRef
import polynote.kernel.Kernel.InterpreterNotStarted
import polynote.kernel.dependency.CoursierFetcher
import polynote.kernel.environment.{Config, CurrentNotebook, CurrentRuntime, CurrentTask, Env, InterpreterEnvironment, PublishResult, PublishStatus}
import polynote.kernel.interpreter.State.Root
import polynote.kernel.interpreter.{Interpreter, State}
import polynote.kernel.interpreter.scal.ScalaInterpreter
import polynote.kernel.util.RefMap
import polynote.messages.{ByteVector32, CellID, HandleType, Lazy, NotebookCell, Streaming, Updating, truncateTinyString}
import polynote.runtime.{LazyDataRepr, ReprsOf, StreamingDataRepr, StringRepr, TableOp, UpdatingDataRepr}
import scodec.bits.ByteVector
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
    TaskManager.queue(s"Cell $id", s"Cell $id", errorWith = TaskStatus.Complete) {

      val run = for {
        _             <- busyState.update(_.setBusy)
        notebookRef   <- CurrentNotebook.access
        notebook      <- notebookRef.get
        cell          <- ZIO(notebook.cell(id))
        interpEnv     <- InterpreterEnvironment.fromKernel(id)
        interpreter   <- getOrLaunch(cell.language, CellID(0)).provideSomeM(Env.enrich[BaseEnv with GlobalEnv with CellEnv](interpEnv: InterpreterEnv))
        state         <- interpreterState.get
        prevCells      = notebook.cells.takeWhile(_.id != cell.id)                                                    // find the latest executed state that correlates to a notebook cell
        prevState      = prevCells.reverse.map(_.id).flatMap(state.at).headOption.getOrElse(latestPredef(state))
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
        _             <- busyState.update(_.setIdle)
      } yield ()

      run.supervised.onInterrupt {
        PublishResult(ErrorResult(new InterruptedException("Execution was interrupted by the user"))).orDie *>
        busyState.update(_.setIdle).orDie
      }.catchAll {
        err =>
          PublishResult(ErrorResult(err)) *> busyState.update(_.setIdle)
      }
    }

  private def latestPredef(state: State) = state.rewindWhile(_.id >= 0) match {
    case s if s.id < 0 => s
    case s             => s.prev
  }

  private def updateState(resultState: State) = {
    interpreterState.update {
      state =>
        val rs = resultState
        val updated = state.insertOrReplace(resultState)
        updated
    }
  }

  override def completionsAt(id: CellID, pos: Int): TaskR[BaseEnv with GlobalEnv with CellEnv, List[Completion]] = {
    for {
      (cell, interp, state) <- cellInterpreter(id, forceStart = true)
      completions           <- interp.completionsAt(cell.content.toString, pos, state)
    } yield completions
  }.catchAll(_ => ZIO.succeed(Nil))

  override def parametersAt(id: CellID, pos: Int): TaskR[BaseEnv with GlobalEnv with CellEnv, Option[Signatures]] = {
    for {
      (cell, interp, state) <- cellInterpreter(id, forceStart = true)
      signatures            <- interp.parametersAt(cell.content.toString, pos, state).mapError(_ => ())
    } yield signatures
  }.catchAll(_ => ZIO.succeed(None))

  override def init(): TaskR[BaseEnv with GlobalEnv with CellEnv, Unit] = TaskManager.run("Predef", "Predef") {
    for {
      publishStatus <- PublishStatus.access
      busyUpdater   <- busyState.discrete.terminateAfter(!_.alive).through(publishStatus.publish).compile.drain.fork
      initialState  <- initScala().onError(err => (PublishResult(ErrorResult(err.squash)) *> busyState.update(_.setIdle)).orDie)
      _             <- initialState.values.map(PublishResult.apply).sequence
      _             <- busyState.update(_.setIdle)
    } yield ()
  }


  override def getHandleData(handleType: HandleType, handleId: Int, count: Int): TaskR[BaseEnv with StreamingHandles, Array[ByteVector32]] =
    handleType match {
      case Lazy =>
        for {
          handle <- ZIO.fromOption(LazyDataRepr.getHandle(handleId)).mapError(_ => new NoSuchElementException(s"Lazy#$handleId"))
        } yield Array(ByteVector32(ByteVector(handle.data.rewind().asInstanceOf[ByteBuffer])))

      case Updating =>
        for {
          handle <- ZIO.fromOption(UpdatingDataRepr.getHandle(handleId))
            .mapError(_ => new NoSuchElementException(s"Updating#$handleId"))
        } yield handle.lastData.map(data => ByteVector32(ByteVector(data.rewind().asInstanceOf[ByteBuffer]))).toArray

      case Streaming => StreamingHandles.getStreamData(handleId, count)
    }

  override def modifyStream(handleId: Int, ops: List[TableOp]): TaskR[BaseEnv with StreamingHandles, Option[StreamingDataRepr]] =
    StreamingHandles.modifyStream(handleId, ops)

  override def releaseHandle(handleType: HandleType, handleId: Int): TaskR[BaseEnv with StreamingHandles, Unit] = handleType match {
    case Lazy => ZIO(LazyDataRepr.releaseHandle(handleId))
    case Updating => ZIO(UpdatingDataRepr.releaseHandle(handleId))
    case Streaming => StreamingHandles.releaseStreamHandle(handleId)
  }

  private def initScala(): TaskR[BaseEnv with GlobalEnv with CellEnv with CurrentTask, State] = for {
    scalaInterp   <- interpreters.get("scala").orDie.get.mapError(_ => new IllegalStateException("No scala interpreter"))
    initialState  <- interpreterState.get
    predefEnv     <- InterpreterEnvironment.fromKernel(initialState.id)
    predefState   <- scalaInterp.init(initialState).provideSomeM(Env.enrich[BaseEnv with GlobalEnv with CellEnv](predefEnv: InterpreterEnv))
    predefState   <- updateValues(predefState)
    _             <- interpreterState.set(predefState)
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
    * the overall result is None unless forceStart is true, in which case the interpreter will be started.
    */
  private def cellInterpreter(id: CellID, forceStart: Boolean = false): ZIO[BaseEnv with GlobalEnv with CellEnv, Unit, (NotebookCell, Interpreter, State)] = {
    for {
      notebook    <- CurrentNotebook.get.orDie
      cell        <- CurrentNotebook.getCell(id)
      state       <- interpreterState.get.orDie
      prevCells    = notebook.cells.takeWhile(_.id != cell.id)
      prevState    = prevCells.reverse.map(_.id).flatMap(state.at).headOption.getOrElse(State.Root)
      interpreter <-
        if (forceStart)
          getOrLaunch(cell.language, id).provideSomeM(Env.enrichM[BaseEnv with GlobalEnv with CellEnv](
            InterpreterEnvironment.noTask(id).widen[InterpreterEnv]))
        else
          interpreters.get(cell.language).orDie.get.mapError(_ => InterpreterNotStarted)
    } yield (cell, interpreter, State.id(id, prevState))
  }.map {
    result => Option(result)
  }.catchAll(_ => ZIO.succeed(None)).get  // TODO: need a real OptionT

  private def getOrLaunch(language: String, at: CellID): TaskR[BaseEnv with GlobalEnv with InterpreterEnv with CurrentNotebook with TaskManager with Interpreter.Factories with Config, Interpreter] =
    interpreters.getOrCreate(language) {
      Interpreter.availableFactories(language)
        .flatMap(facs => chooseInterpreterFactory(facs).mapError(_ => new UnsupportedOperationException(s"No available interpreter for $language")))
        .flatMap {
          factory => TaskManager.run(s"Launch$$$language", factory.languageName,s"Starting ${factory.languageName} interpreter") {
            for {
              interpreter  <- factory().provideSomeM(Env.enrich[BaseEnv with GlobalEnv with CurrentNotebook with TaskManager with Config with CurrentTask](compilerProvider))
              currentState <- interpreterState.get
              insertStateAt = currentState.rewindWhile(s => s.id != at && !(s eq Root))
              lastPredef    = currentState.lastPredef
              newState     <- interpreter.init(State.predef(insertStateAt, lastPredef))
              _            <- interpreterState.update(_.insert(insertStateAt.id, newState))
            } yield interpreter
          }
      }
    }

  protected def chooseInterpreterFactory(factories: List[Interpreter.Factory]): ZIO[Any, Unit, Interpreter.Factory] =
    ZIO.fromOption(factories.filterNot(_.requireSpark).headOption)

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
          if (value.value != null) {
            val reprs = instanceMap.get(value.name).toList.flatMap(_.apply(value.value).toList) match {
              case reprs if reprs.exists(_.isInstanceOf[StringRepr]) => reprs
              case reprs => reprs :+ StringRepr(truncateTinyString(Option(value.value).flatMap(v => Option(v.toString)).getOrElse("null")))
            }
            value.copy(reprs = reprs)
          } else value
        }

        state.updateValues(updateValue)
    }

  }
}

class LocalKernelFactory extends Kernel.Factory.Service {

  def apply(): TaskR[BaseEnv with GlobalEnv with CellEnv, Kernel] = for {
    scalaDeps    <- CoursierFetcher.fetch("scala")
    compiler     <- ScalaCompiler.provider(scalaDeps.map(_._2))
    busyState    <- SignallingRef[Task, KernelBusyState](KernelBusyState(busy = true, alive = true))
    interpreters <- RefMap.empty[String, Interpreter]
    _            <- interpreters.getOrCreate("scala")(ScalaInterpreter().provide(compiler))
    interpState  <- Ref[Task].of[State](State.predef(State.Root, State.Root))
  } yield new LocalKernel(compiler, interpState, interpreters, busyState)

}

object LocalKernel extends LocalKernelFactory