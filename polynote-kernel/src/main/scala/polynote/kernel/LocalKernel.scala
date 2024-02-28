package polynote.kernel

import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import polynote.kernel.Kernel.InterpreterNotStarted
import polynote.kernel.dependency.CoursierFetcher
import polynote.kernel.environment._
import polynote.kernel.interpreter.State.Root
import polynote.kernel.interpreter.scal.ScalaInterpreter
import polynote.kernel.interpreter.{CellExecutor, Interpreter, InterpreterState, State}
import polynote.kernel.logging.Logging
import polynote.kernel.task.TaskManager
import polynote.kernel.util.RefMap
import polynote.messages.{ByteVector32, CellID, DefinitionLocation, HandleType, Lazy, NotebookCell, ShortString, Streaming, TinyString, Updating, truncateTinyString}
import polynote.runtime._
import scodec.bits.ByteVector
import zio.blocking.{Blocking, effectBlocking}
import zio.clock.Clock
import zio.duration.Duration
import zio.stream.SubscriptionRef
import zio.{Promise, RIO, Task, UIO, URIO, ZIO, ZLayer}


class LocalKernel private[kernel] (
  scalaCompiler: ScalaCompiler,
  interpreterState: InterpreterState.Service,
  interpreters: RefMap[String, Interpreter],
  busyState: SubscriptionRef[KernelBusyState],
  closed: Promise[Throwable, Unit]
) extends Kernel {

  def currentTime: ZIO[Clock, Nothing, Long] = ZIO.accessM[Clock](_.get.currentTime(TimeUnit.MILLISECONDS))

  override def queueCell(id: CellID): RIO[BaseEnv with GlobalEnv with CellEnv, Task[Unit]] = PublishStatus.access.flatMap {
    publishStatus =>
      val notifyCancelled = publishStatus.publish(CellStatusUpdate(id, Complete))
      val notifyErrored   = publishStatus.publish(CellStatusUpdate(id, ErrorStatus))
      val queueTask: RIO[BaseEnv with GlobalEnv with CellEnv, Task[Unit]] = TaskManager.queue(s"Cell $id", s"Cell $id", errorWith = _ => _.completed) {
        currentTime.flatMap {
          startTime =>
            def publishEndTime: RIO[PublishResult with Clock, Unit] =
              currentTime.flatMap(endTime => PublishResult(ExecutionInfo(startTime, Some(endTime))))

            val run = for {
              _             <- busyState.ref.update(s => ZIO.succeed(s.setBusy))
              _             <- PublishStatus(CellStatusUpdate(id, Running))
              notebook      <- CurrentNotebook.get
              cell          <- ZIO(notebook.cell(id))
              interpreter   <- getOrLaunch(cell.language, CellID(0))
              state         <- interpreterState.getState
              prevCells      = notebook.cells.takeWhile(_.id != cell.id)                                                  // find the latest executed state that correlates to a notebook cell
              prevState      = prevCells.reverse.map(_.id).flatMap(state.at).headOption.getOrElse(latestPredef(state))
              _             <- PublishResult(ClearResults())
              _             <- PublishResult(ExecutionInfo(startTime, None))
              _             <- CurrentNotebook.get
              initialState   = State.id(id, prevState)                                                                    // run the cell while capturing outputs
              resultState   <- (interpreter.run(cell.content.toString, initialState).provideSomeLayer(CellExecutor.layer(scalaCompiler.classLoader)) >>= updateValues)
                .ensuring(CurrentRuntime.access.flatMap(rt => ZIO.effectTotal(rt.clearExecutionStatus())))
              _             <- updateState(resultState)
              _             <- ZIO.collectAll_(resultState.values.map(PublishResult.apply))                              // publish the result values
              _             <- publishEndTime
              _             <- notifyCancelled
              notebook      <- CurrentNotebook.get
              _             <- busyState.ref.update(s => ZIO.succeed(s.setIdle))
            } yield ()

            run.provideSomeLayer(CurrentRuntime.layer(id)).onInterrupt {
              PublishResult(ErrorResult(new InterruptedException("Execution was interrupted by the user"))).orDie *>
              notifyErrored *>
              busyState.ref.update(s => ZIO.succeed(s.setIdle)) *>
              publishEndTime.orDie
            }.catchAll {
              err =>
                PublishResult(ErrorResult(err)) *>
                  notifyErrored *>
                  busyState.ref.update(s => ZIO.succeed(s.setIdle)) *>
                  publishEndTime.orDie
            }
        }
      }

      for {
        inner <- queueTask
        _     <- publishStatus.publish(CellStatusUpdate(id, Queued))
      } yield inner.onInterrupt(notifyCancelled)
  }

  private def latestPredef(state: State) = state.rewindWhile(_.id >= 0) match {
    case s if s.id < 0 => s
    case s             => s.prev
  }

  private def updateState(resultState: State) = {
    // release any handles from a previous run of this cell
    val releaseExisting = interpreterState.getState.flatMap {
      prevState => ZIO.foreach_(prevState.at(resultState.id)) {
        prevCellState => releaseReprs(prevCellState.values.flatMap(_.reprs))
      }
    }

    releaseExisting *> interpreterState.updateState {
      state => state.insertOrReplace(resultState)
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

  override def goToDefinition(source: Either[String, CellID], pos: Int): TaskC[List[DefinitionLocation]] =
    CurrentNotebook.path.flatMap {
      notebookPath =>
        def asDepURL(lang: String)(location: DefinitionLocation): DefinitionLocation = location.uri match {
          case uri if uri startsWith "#" => location
          case uri => location.copy(uri = s"/dependency/$notebookPath?lang=$lang&dependency=$uri")
        }


        source match {
          case Right(id) =>
            val lookup = for {
              (cell, interp, state) <- cellInterpreter(id, forceStart = true).onError(cause => Logging.error(cause))
              locations             <- interp.goToDefinition(cell.content.toString, pos, state)
            } yield locations.map(asDepURL(cell.language))
            lookup.onError(Logging.error).catchAll(_ => ZIO.succeed(Nil))

          case Left(uri) =>
            val ext = uri.split('.').last
            val lookup = for {
              interps        <- interpreters.entries
              (lang, interp) <- ZIO.succeed(interps.find(_._2.fileExtensions.contains(ext))).someOrFailException
              locations      <- interp.goToDependencyDefinition(uri, pos)
            } yield locations.map(asDepURL(lang))
            lookup.onError(Logging.error).catchAll(_ => ZIO.succeed(Nil))
        }
    }

  override def dependencySource(language: String, dependency: String): RIO[BaseEnv with GlobalEnv, String] = for {
    interpreter <- interpreters.get(language).someOrFailException
    source      <- interpreter.getDependencyContent(dependency)
  } yield source

  override def init(): RIO[BaseEnv with GlobalEnv with CellEnv, Unit] = TaskManager.run("Predef", "Predef") {
    for {
      publishStatus <- PublishStatus.access
      busyUpdater   <- busyState.changes.haltWhen(closed.await.run).foreachWhile(s => publishStatus.publish(s).as(s.alive)).forkDaemon
      initialState  <- initScala().onError(err => (PublishResult(ErrorResult(err.squash)) *> busyState.ref.update(s => ZIO.succeed(s.setIdle))).orDie)
      _             <- ZIO.foreach_(initialState.values)(PublishResult.apply)
      _             <- busyState.ref.update(s => ZIO.succeed(s.setIdle))
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
    initialState  <- interpreterState.getState
    predefState   <- scalaInterp.init(initialState).provideSomeLayer[BaseEnv with GlobalEnv with CellEnv with CurrentTask](CurrentRuntime.noRuntime)
    predefState   <- updateValues(predefState)
    _             <- interpreterState.updateState(_ => predefState)
  } yield predefState

  override def shutdown(): Task[Unit] = for {
    _            <- busyState.ref.update(s => ZIO.succeed(s.setBusy))
    interpreters <- interpreters.values
    _            <- ZIO.foreachPar_(interpreters)(_.shutdown())
    _            <- busyState.ref.set(KernelBusyState(busy = false, alive = false))
    _            <- closed.succeed(())
  } yield ()

  override def status(): Task[KernelBusyState] = busyState.ref.get

  override def values(): Task[List[ResultValue]] = interpreterState.getState.map(_.scope)

  /**
    * Get the cell with the given ID along with its interpreter and state. If its interpreter hasn't been started,
    * the overall result is None unless forceStart is true, in which case the interpreter will be started.
    */
  private def cellInterpreter(id: CellID, forceStart: Boolean = false): ZIO[BaseEnv with GlobalEnv with CellEnv, NoSuchElementException, (NotebookCell, Interpreter, State)] = {
    for {
      notebook    <- CurrentNotebook.get
      cell        <- CurrentNotebook.getCell(id)
      state       <- interpreterState.getState
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
  }.catchAll(err => Logging.error(err).as(None)).someOrFailException  // TODO: need a real OptionT

  private def getOrLaunch(language: String, at: CellID): RIO[BaseEnv with GlobalEnv with InterpreterEnv with CurrentNotebook with TaskManager, Interpreter] =
    interpreters.getOrCreate(language) {
      Interpreter.availableFactories(language)
        .flatMap(facs => chooseInterpreterFactory(facs).mapError(_ => new UnsupportedOperationException(s"No available interpreter for $language")))
        .flatMap {
          factory => TaskManager.run(s"Launch$$$language", factory.languageName,s"Starting ${factory.languageName} interpreter") {
            for {
              interpreter  <- factory().provideSomeLayer[BaseEnv with GlobalEnv with CurrentNotebook with TaskManager with CurrentTask](ZLayer.succeed(scalaCompiler))
              currentState <- interpreterState.getState
              insertStateAt = currentState.rewindWhile(s => s.id != at && !(s eq Root))
              lastPredef    = currentState.lastPredef
              newState     <- interpreter.init(State.predef(insertStateAt, lastPredef))
              _            <- interpreterState.updateState(_.insert(insertStateAt.id, newState))
            } yield interpreter
          }
      }
    }

  protected def chooseInterpreterFactory(factories: List[Interpreter.Factory]): ZIO[Any, Option[Nothing], Interpreter.Factory] =
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
          val makeStringRepr: RIO[Logging, StringRepr] =
            ZIO(StringRepr(ShortString.truncatePretty(Option(value.value).flatMap(v => Option(v.toString)).getOrElse("null"))))
              .onError(err => Logging.error("Error creating String repr", err))
              .catchAll {
                err => ZIO.succeed(StringRepr(s"Error while representing ${value.name} as a String: ${err.getClass.getName}. Try executing ${value.name}.toString() to see the stack trace."))
              }

          val onlyStringRepr = makeStringRepr.map(stringRepr => value.copy(reprs = List(stringRepr)))
          if (value.value != null) {
            ZIO.effectTotal(instanceMap.get(value.name)).flatMap {
              case Some(instance) =>
                effectBlocking(instance.apply(value.value))
                  .onError(err => Logging.error("Error creating result reprs", err))
                  .catchAll(_ => ZIO.succeed(Array.empty[ValueRepr])).map(_.toList).flatMap {
                    case reprs if reprs.exists(_.isInstanceOf[StringRepr]) => ZIO.succeed(reprs)
                    case reprs => makeStringRepr.map(stringRepr => reprs :+ stringRepr)
                  }.flatMap(filterReprs).map {
                    reprs => value.copy(reprs = reprs)
                  }

              case None => onlyStringRepr
            }
          } else onlyStringRepr
        }

        state.updateValuesM(updateValue)
    }

  }

  // remove any invalid reprs from the given list, and release any handles owned by invalid reprs.
  // returns the filtered (valid) reprs.
  private def filterReprs(reprs: List[ValueRepr]): URIO[Logging, List[ValueRepr]] = reprs.partition(isValidRepr) match {
    case (valid, invalid) => releaseReprs(invalid).as(valid)
  }

  private def releaseReprs(reprs: List[ValueRepr]): URIO[Logging, Unit] = ZIO.foreach_(reprs) {
    case StreamingDataRepr(handleId, _, _) => ZIO.effect(StreamingDataRepr.releaseHandle(handleId)).catchAll(Logging.error)
    case UpdatingDataRepr(handleId, _)     => ZIO.effect(UpdatingDataRepr.releaseHandle(handleId)).catchAll(Logging.error)
    case LazyDataRepr(handleId, _, _)      => ZIO.effect(LazyDataRepr.releaseHandle(handleId)).catchAll(Logging.error)
    case _ => ZIO.unit
  }

  private def isValidRepr(repr: ValueRepr): Boolean = repr match {
    case StreamingDataRepr(_, StructType(Nil), _) => false
    case UpdatingDataRepr(_, StructType(Nil))     => false
    case LazyDataRepr(_, StructType(Nil), _)      => false
    case DataRepr(StructType(Nil), _)             => false
    case _ => true
  }

  override def awaitClosed: Task[Unit] = closed.await
}

class LocalKernelFactory extends Kernel.Factory.LocalService {

  def apply(): RIO[BaseEnv with GlobalEnv with CellEnv, Kernel] = for {
    scalaDeps         <- CoursierFetcher.fetch("scala")
    compiler          <- ScalaCompiler(scalaDeps, Nil)
    busyState         <- SubscriptionRef.make(KernelBusyState(busy = true, alive = true))
    interpreters      <- RefMap.empty[String, Interpreter]
    mkScala            = ScalaInterpreter().provideSomeLayer[BaseEnv with Config with TaskManager](ZLayer.succeed(compiler))
    _                 <- interpreters.getOrCreate("scala")(mkScala)
    interpState       <- InterpreterState.access
    closed            <- Promise.make[Throwable, Unit]
  } yield new LocalKernel(compiler, interpState, interpreters, busyState, closed)

}

object LocalKernel extends LocalKernelFactory