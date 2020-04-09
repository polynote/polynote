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
import polynote.kernel.environment.{Config, CurrentNotebook, CurrentRuntime, CurrentTask, Env, PublishResult, PublishStatus}
import polynote.kernel.interpreter.State.Root
import polynote.kernel.interpreter.{CellExecutor, Interpreter, State}
import polynote.kernel.interpreter.scal.ScalaInterpreter
import polynote.kernel.logging.Logging
import polynote.kernel.task.TaskManager
import polynote.kernel.util.RefMap
import polynote.messages.{ByteVector32, CellID, HandleType, Lazy, NotebookCell, Streaming, Updating, truncateTinyString}
import polynote.runtime.{LazyDataRepr, ReprsOf, StreamingDataRepr, StringRepr, TableOp, UpdatingDataRepr, ValueRepr}
import scodec.bits.ByteVector
import zio.{Promise, RIO, RManaged, Task, ZIO, ZLayer}
import zio.blocking.{Blocking, effectBlocking}
import zio.clock.Clock
import zio.duration.Duration
import zio.interop.catz._


class LocalKernel private[kernel] (
  scalaCompiler: ScalaCompiler,
  interpreterState: Ref[Task, State],
  interpreters: RefMap[String, Interpreter],
  busyState: SignallingRef[Task, KernelBusyState],
  closed: Promise[Throwable, Unit]
) extends Kernel {


  def currentTime: ZIO[Clock, Nothing, Long] = ZIO.accessM[Clock](_.get.currentTime(TimeUnit.MILLISECONDS))

  override def queueCell(id: CellID): RIO[BaseEnv with GlobalEnv with CellEnv, Task[Unit]] =
    TaskManager.queue(s"Cell $id", s"Cell $id", errorWith = _ => _.completed) {
      currentTime.flatMap {
        startTime =>
          def publishEndTime: RIO[PublishResult with Clock, Unit] =
            currentTime.flatMap(endTime => PublishResult(ExecutionInfo(startTime, Some(endTime))))

          val run = for {
            _             <- busyState.update(_.setBusy)
            notebook      <- CurrentNotebook.get
            cell          <- ZIO(notebook.cell(id))
            interpreter   <- getOrLaunch(cell.language, CellID(0))
            state         <- interpreterState.get
            prevCells      = notebook.cells.takeWhile(_.id != cell.id)                                                  // find the latest executed state that correlates to a notebook cell
            prevState      = prevCells.reverse.map(_.id).flatMap(state.at).headOption.getOrElse(latestPredef(state))
            _             <- PublishResult(ClearResults())
            _             <- PublishResult(ExecutionInfo(startTime, None))
            _             <- CurrentNotebook.get
            initialState   = State.id(id, prevState)                                                                    // run the cell while capturing outputs
            resultState   <- (interpreter.run(cell.content.toString, initialState).provideSomeLayer(CellExecutor.layer(scalaCompiler.classLoader)) >>= updateValues)
              .ensuring(CurrentRuntime.access.flatMap(rt => ZIO.effectTotal(rt.clearExecutionStatus())))
            _             <- publishEndTime
            _             <- updateState(resultState)
            _             <- resultState.values.map(PublishResult.apply).sequence.unit                                  // publish the result values
            _             <- busyState.update(_.setIdle)
          } yield ()

          run.provideSomeLayer(CurrentRuntime.layer(id)).onInterrupt { _ =>
            PublishResult(ErrorResult(new InterruptedException("Execution was interrupted by the user"))).orDie *>
            busyState.update(_.setIdle).orDie *>
            publishEndTime.orDie
          }.catchAll {
            err =>
              PublishResult(ErrorResult(err)) *> busyState.update(_.setIdle) *> publishEndTime.orDie
          }
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

  override def completionsAt(id: CellID, pos: Int): RIO[BaseEnv with GlobalEnv with CellEnv, List[Completion]] = {
    for {
      (cell, interp, state) <- cellInterpreter(id, forceStart = true)
      completions           <- interp.completionsAt(cell.content.toString, pos, state)
    } yield completions
  }.catchAll(_ => ZIO.succeed(Nil))

  override def parametersAt(id: CellID, pos: Int): RIO[BaseEnv with GlobalEnv with CellEnv, Option[Signatures]] = {
    for {
      (cell, interp, state) <- cellInterpreter(id, forceStart = true)
      signatures            <- interp.parametersAt(cell.content.toString, pos, state).mapError(_ => ())
    } yield signatures
  }.catchAll(_ => ZIO.succeed(None))

  override def init(): RIO[BaseEnv with GlobalEnv with CellEnv, Unit] = TaskManager.run("Predef", "Predef") {
    for {
      publishStatus <- PublishStatus.access
      busyUpdater   <- busyState.discrete.terminateAfter(!_.alive).through(publishStatus.publish).compile.drain.forkDaemon
      initialState  <- initScala().onError(err => (PublishResult(ErrorResult(err.squash)) *> busyState.update(_.setIdle)).orDie)
      _             <- initialState.values.map(PublishResult.apply).sequence
      _             <- busyState.update(_.setIdle)
    } yield ()
  }


  override def getHandleData(handleType: HandleType, handleId: Int, count: Int): RIO[BaseEnv with StreamingHandles, Array[ByteVector32]] =
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

  override def modifyStream(handleId: Int, ops: List[TableOp]): RIO[BaseEnv with StreamingHandles, Option[StreamingDataRepr]] =
    StreamingHandles.modifyStream(handleId, ops)

  override def releaseHandle(handleType: HandleType, handleId: Int): RIO[BaseEnv with StreamingHandles, Unit] = handleType match {
    case Lazy => ZIO(LazyDataRepr.releaseHandle(handleId))
    case Updating => ZIO(UpdatingDataRepr.releaseHandle(handleId))
    case Streaming => StreamingHandles.releaseStreamHandle(handleId)
  }

  private def initScala(): RIO[BaseEnv with GlobalEnv with CellEnv with CurrentTask, State] = for {
    scalaInterp   <- interpreters.get("scala").get.mapError(_ => new IllegalStateException("No scala interpreter"))
    initialState  <- interpreterState.get
    predefState   <- scalaInterp.init(initialState).provideSomeLayer[BaseEnv with GlobalEnv with CellEnv with CurrentTask](CurrentRuntime.noRuntime)
    predefState   <- updateValues(predefState)
    _             <- interpreterState.set(predefState)
  } yield predefState

  override def shutdown(): Task[Unit] = ZIO.whenM(closed.succeed(())) {
    for {
      _            <- busyState.update(_.setBusy)
      interpreters <- interpreters.values
      _            <- ZIO.foreachPar_(interpreters)(_.shutdown())
      _            <- busyState.set(KernelBusyState(busy = false, alive = false))
    } yield ()
  }

  override def status(): Task[KernelBusyState] = busyState.get

  override def values(): Task[List[ResultValue]] = interpreterState.get.map(_.scope)

  /**
    * Get the cell with the given ID along with its interpreter and state. If its interpreter hasn't been started,
    * the overall result is None unless forceStart is true, in which case the interpreter will be started.
    */
  private def cellInterpreter(id: CellID, forceStart: Boolean = false): ZIO[BaseEnv with GlobalEnv with CellEnv, NoSuchElementException, (NotebookCell, Interpreter, State)] = {
    for {
      notebook    <- CurrentNotebook.get.orDie
      cell        <- CurrentNotebook.getCell(id)
      state       <- interpreterState.get.orDie
      prevCells    = notebook.cells.takeWhile(_.id != cell.id)
      prevState    = prevCells.reverse.map(_.id).flatMap(state.at).headOption.getOrElse(latestPredef(state))
      interpreter <-
        if (forceStart)
          getOrLaunch(cell.language, id).provideSomeLayer(CurrentRuntime.layer(id))
        else
          interpreters.get(cell.language).get.mapError(_ => InterpreterNotStarted)
    } yield (cell, interpreter, State.id(id, prevState))
  }.provideSomeLayer[BaseEnv with GlobalEnv with CellEnv](CurrentTask.none).map {
    result => Option(result)
  }.catchAll(_ => ZIO.succeed(None)).someOrFailException  // TODO: need a real OptionT

  private def getOrLaunch(language: String, at: CellID): RIO[BaseEnv with GlobalEnv with InterpreterEnv with CurrentNotebook with TaskManager, Interpreter] =
    interpreters.getOrCreate(language) {
      Interpreter.availableFactories(language)
        .flatMap(facs => chooseInterpreterFactory(facs).mapError(_ => new UnsupportedOperationException(s"No available interpreter for $language")))
        .flatMap {
          factory => TaskManager.run(s"Launch$$$language", factory.languageName,s"Starting ${factory.languageName} interpreter") {
            for {
              interpreter  <- factory().provideSomeLayer[BaseEnv with GlobalEnv with CurrentNotebook with TaskManager with CurrentTask](ZLayer.succeed(scalaCompiler))
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
  private def updateValues(state: State): RIO[Blocking with Logging with Clock, State] = {
    import scalaCompiler.global, global.{appliedType, typeOf}
    val (names, types) = state.values.map {v =>
      v.name -> appliedType(typeOf[ReprsOf[Any]].typeConstructor, v.scalaType.asInstanceOf[global.Type])
    }.toMap.toList.unzip
    scalaCompiler.inferImplicits(types).timeout(Duration(3, TimeUnit.SECONDS)).flatMap {
      case None =>
        state.updateValuesM {
          resultValue =>
            val fallback = ZIO.succeed(resultValue)
            ZIO(resultValue.copy(reprs = List(StringRepr(resultValue.value.toString))))
              .orElse(fallback)
              .timeout(Duration(200, TimeUnit.MILLISECONDS)).get
              .orElse(fallback)
        }
      case Some(instances) =>
        val instanceMap = names.zip(instances).collect {
          case (name, Some(instance)) => name -> instance.asInstanceOf[ReprsOf[Any]]
        }.toMap

        def updateValue(value: ResultValue): RIO[Blocking with Logging, ResultValue] = {
          def stringRepr = StringRepr(truncateTinyString(Option(value.value).flatMap(v => Option(v.toString)).getOrElse("null")))
          if (value.value != null) {
            ZIO.effectTotal(instanceMap.get(value.name)).flatMap {
              case Some(instance) =>
                effectBlocking(instance.apply(value.value))
                  .onError(err => Logging.error("Error creating result reprs", err))
                  .catchAll(_ => ZIO.succeed(Array.empty[ValueRepr])).map(_.toList).map {
                    case reprs if reprs.exists(_.isInstanceOf[StringRepr]) => reprs
                    case reprs => reprs :+ stringRepr
                  }.map {
                    reprs => value.copy(reprs = reprs)
                  }

              case None =>
                ZIO.succeed(value.copy(reprs = List(stringRepr)))
            }
          } else ZIO.succeed(value.copy(reprs = List(stringRepr)))
        }

        state.updateValuesM(updateValue)
    }

  }

  override def awaitClosed: Task[Unit] = closed.await
}

class LocalKernelFactory extends Kernel.Factory.LocalService {

  def make: RIO[BaseEnv with GlobalEnv with CellEnv, Kernel] = for {
    scalaDeps    <- CoursierFetcher.fetch("scala")
    (main, transitive) = scalaDeps.partition(_._1)
    compiler     <- ScalaCompiler(main.map(_._3), transitive.map(_._3), Nil)
    busyState    <- SignallingRef[Task, KernelBusyState](KernelBusyState(busy = true, alive = true))
    interpreters <- RefMap.empty[String, Interpreter]
    _            <- interpreters.getOrCreate("scala")(ScalaInterpreter().provideSomeLayer[Blocking](ZLayer.succeed(compiler)))
    interpState  <- Ref[Task].of[State](State.predef(State.Root, State.Root))
    closed       <- Promise.make[Throwable, Unit]
  } yield new LocalKernel(compiler, interpState, interpreters, busyState, closed)

  def apply(): RManaged[BaseEnv with GlobalEnv with CellEnv, Kernel] = make.toManaged(_.shutdown().orDie)

}

object LocalKernel extends LocalKernelFactory