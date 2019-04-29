package polynote.kernel

import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.{ConcurrentHashMap, Executors}

import cats.effect.concurrent.Semaphore
import cats.effect.{Clock, ContextShift, IO}
import cats.instances.list._
import cats.syntax.apply._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import fs2.Stream
import fs2.concurrent.{Enqueue, Queue, SignallingRef}
import org.log4s.{Logger, getLogger}
import polynote.buildinfo.BuildInfo
import polynote.config.PolynoteConfig
import polynote.kernel.PolyKernel.EnqueueSome
import polynote.kernel.lang.LanguageInterpreter
import polynote.kernel.lang.scal.{ScalaInterpreter, ScalaSource}
import polynote.kernel.util._
import polynote.messages._
import polynote.runtime.{LazyDataRepr, StreamingDataRepr, TableOp, UpdatingDataRepr}
import scodec.bits.ByteVector

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.MILLISECONDS
import scala.collection.immutable.SortedMap
import scala.reflect.io.{AbstractFile, VirtualDirectory}
import scala.tools.nsc.Settings

class PolyKernel private[kernel] (
  private val notebookContext: SignallingRef[IO, NotebookContext],
  val kernelContext: KernelContext,
  val outputDir: AbstractFile,
  dependencies: Map[String, List[(String, File)]],
  val statusUpdates: Publish[IO, KernelStatusUpdate],
  availableInterpreters: Map[String, LanguageInterpreter.Factory[IO]] = Map.empty,
  config: PolynoteConfig
) extends KernelAPI[IO] {

  protected val logger: Logger = getLogger

  protected implicit val contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.fromExecutorService(Executors.newCachedThreadPool()))

  private val launchingInterpreter = Semaphore[IO](1).unsafeRunSync()
  private val interpreters = new ConcurrentHashMap[String, LanguageInterpreter[IO]]()

  private val clock: Clock[IO] = Clock.create

  /**
    * The task queue, which tracks currently running and queued kernel tasks (i.e. cells to run)
    */
  protected lazy val taskManager: TaskManager = TaskManager(statusUpdates).unsafeRunSync()

  private def runPredef(interp: LanguageInterpreter[IO], language: String): IO[Stream[IO, Result]] =
    interp.init() *> notebookContext.get.flatMap { nbCtx =>
      // Since there can be multiple predefs (one per interpreter), they get monotonically decreasing negative cell IDs
      // starting with -1. Get the smallest existing ID, make sure it's 0 or less, and subtract one.
      val cellId = math.min(0, nbCtx.first.map(_.id).getOrElse(0.toShort)) - 1

      CellContext(cellId, None).flatMap {
        cellContext =>
          interp.predefCode match {
            case Some(code) =>
              taskManager.runTaskIO(s"Predef $language", s"Predef ($language)")(_ => interp.runCode(cellContext, code)).map {
                results => results.collect {
                  case v: ResultValue => v
                }.through(cellContext.results.tap)
              } <* notebookContext.update { ctx =>
                ctx.insertFirst(cellContext)
                ctx
              }

            case None => IO.pure(Stream.empty)
          }
      }
    }

  protected def getInterpreter(language: String): Option[LanguageInterpreter[IO]] = Option(interpreters.get(language))

  protected def getOrLaunchInterpreter(language: String): IO[(LanguageInterpreter[IO], Stream[IO, Result])] = {
    getInterpreter(language)
      .map(interp => IO.pure(interp -> Stream.empty))
      .getOrElse {
        val startInterp = launchingInterpreter.acquire.bracket { _ =>
          Option(interpreters.get(language)).map(interp => IO.pure(interp -> Stream.empty)).getOrElse {
            for {
              factory <- IO.fromEither(Either.fromOption(availableInterpreters.get(language), new RuntimeException(s"No interpreter for language $language")))
              interp  <- taskManager.runTask(s"Interpreter$$$language", s"Starting $language interpreter")(_ => factory.apply(dependencies.getOrElse(language, Nil), kernelContext))
              _        = interpreters.put(language, interp)
              results  <- runPredef(interp, language)
            } yield (interp, results)
          }
        } {
          _ => launchingInterpreter.release
        }

        if (language == "scala") startInterp else {
          // ensure scala interpreter is launched, because it is required to make JVM modules for other interpreters
          getOrLaunchInterpreter("scala").flatMap {
            case (_, scalaPredefResults) => startInterp.map {
              case (interp, interpPredefResults) => interp -> (scalaPredefResults ++ interpPredefResults)
            }
          }
        }
      }
  }

  /**
    * Perform a task upon a notebook cell, if the interpreter for that cell has already been launched. If the interpreter
    * hasn't been launched, the action won't be performed and the result will be None.
    */
  protected def withLaunchedInterpreter[A](cell: NotebookCell)(fn: (NotebookCell, LanguageInterpreter[IO]) => IO[A]): IO[Option[A]] =
    getInterpreter(cell.language)
      .fold(IO.pure(Option.empty[A])) {
        interp =>
          fn(cell, interp).map(result => Option(result))
      }

  /**
    * Perform a task upon a notebook cell, launching the interpreter if necessary. Since this may result in the interpreter's
    * predef code being executed, and that might produce results, the caller must be prepared to handle the result stream
    * (which is passed into the given task)
    */
  protected def withInterpreter[A](cell: NotebookCell)(fn: (NotebookCell, LanguageInterpreter[IO], Stream[IO, Result]) => IO[A])(ifText: => IO[A]): IO[A] = {
    if (cell.language != "text") {
      for {
        launch   <- getOrLaunchInterpreter(cell.language)
        (interp, predefResults) = launch
        result   <- fn(cell, interp, predefResults)
      } yield result
    } else ifText
  }

  def startInterpreterFor(cell: NotebookCell): IO[Stream[IO, Result]] = withInterpreter(cell) {
    (_, _, results) => IO.pure(results)
  } (IO.pure(Stream.empty))

  def runCell(cell: NotebookCell): IO[Stream[IO, Result]] = queueCell(cell).flatten

  def queueCell(cell: NotebookCell): IO[IO[Stream[IO, Result]]] = {
    taskManager.queueTaskStream(s"Cell ${cell.id}", s"Cell ${cell.id}", s"Running ${cell.id}") {
      taskInfo =>
        Queue.unbounded[IO, Option[Result]].map {
          oq =>
            val oqSome = new EnqueueSome(oq)
            val run = withLaunchedInterpreter(cell) {
              (cell, interp) =>
                // TODO: should this be something the interpreter has to do? Without this we wouldn't even need to allocate a queue here
                val runningTaskInfo = taskInfo.copy(status = TaskStatus.Running)
                polynote.runtime.Runtime.setDisplayer((mime, content) => oqSome.enqueue1(Output(mime, content)).unsafeRunSync())
                polynote.runtime.Runtime.setProgressSetter {
                  (progress, detail) =>
                    val newDetail = Option(detail).getOrElse(taskInfo.detail)
                    statusUpdates.publish1(UpdatedTasks(runningTaskInfo.copy(detail = newDetail, progress = (progress * 255).toByte) :: Nil)).unsafeRunSync()
                }
                polynote.runtime.Runtime.setExecutionStatusSetter {
                  optPos => statusUpdates.publish1(ExecutionStatus(cell.id, optPos)).unsafeRunAsyncAndForget()
                }

                CellContext(cell.id).flatMap {
                  cellContext =>
                    notebookContext.modify { nbctx =>
                      val prevCtx = nbctx.tryUpdate(cellContext)
                      (nbctx, prevCtx)
                    }.flatMap {
                      prevContext =>
                        val results = interp.runCode(
                          cellContext,
                          cell.content.toString
                        ).map {
                          results => results.through(cellContext.results.tapResults).evalTap {
                            case ResultValue(name, _, _, _, value, _, _) => IO(polynote.runtime.Runtime.putValue(name, value))
                            case _ => IO.unit
                          }
                        }.handleErrorWith {
                          err =>
                            notebookContext.update { nbctx =>
                              prevContext.map(nbctx.tryUpdate)
                              nbctx
                            } *> IO.raiseError(err) // restore previous context on error
                        }

                        for {
                          start  <- clock.realTime(MILLISECONDS)
                          res    <- results
                        } yield {
                          Stream.emit(ExecutionInfo(start, None)) ++ res.onComplete {
                            Stream.eval {
                              for {
                                end <- clock.realTime(MILLISECONDS)
                              } yield ExecutionInfo(start, Option(end))
                            }
                          }.onFinalize(statusUpdates.publish1(ExecutionStatus(cell.id, None))).onFinalize {
                            // if the interpreter didn't make a module for the cell, use the scala interpreter to make one
                            cellContext.module.tryGet.flatMap {
                              case Some(_) => IO.unit
                              case None =>
                                getInterpreter("scala") match {
                                  case Some(interp: ScalaInterpreter) =>
                                    import interp.kernelContext.global.Quasiquote
                                    val exports = cellContext.resultValues.foldLeft(SortedMap.empty[String, interp.kernelContext.global.Type]) {
                                      (accum, rv) => accum + (rv.name -> rv.scalaType.asInstanceOf[interp.kernelContext.global.Type])
                                    }.toList.map {
                                      case (name, typ) =>
                                        val termName = interp.kernelContext.global.TermName(name)
                                        q"val $termName: $typ = _root_.polynote.runtime.Runtime.getValue(${name.toString}).asInstanceOf[$typ]"
                                    }
                                    // NOTE: IntelliJ doesn't like the line below but it's actually fine.
                                    interp.compileAndInit(ScalaSource.fromTrees(interp.kernelContext)(cellContext, interp.notebookPackageName, exports))
                                  case _ => IO.raiseError(new IllegalStateException("No scala interpreter"))
                                }
                            }


                          }
                        }
                    }
                }
            }.map {
              _.getOrElse(Stream.empty)
            }.handleErrorWith(ErrorResult.toStream).map {
              _ ++ Stream.eval(oq.enqueue1(None)).drain
            }
            Stream.emit(ClearResults()) ++ Stream(oq.dequeue.unNoneTerminate, Stream.eval(run).flatten).parJoinUnbounded
        }
    }
  }

  // TODO: better control execution order of tasks, and use parJoinUnbounded instead
  def runCells(cells: List[NotebookCell]): IO[Stream[IO, Result]] =
    cells.map { cell =>
      runCell(cell)
    }.sequence.map { runs =>
      Stream.emits(runs).flatten
    }

  def completionsAt(cell: NotebookCell, pos: Int): IO[List[Completion]] = withLaunchedInterpreter(cell) {
    (cell, interp) =>
      notebookContext.get.flatMap(_.getIO(cell.id)).flatMap(ctx => interp.completionsAt(ctx, cell.content.toString, pos))
  }.map(_.getOrElse(Nil))

  def parametersAt(cell: NotebookCell, pos: Int): IO[Option[Signatures]] = withLaunchedInterpreter(cell) {
    (cell, interp) =>
      notebookContext.get.flatMap(_.getIO(cell.id)).flatMap(ctx => interp.parametersAt(ctx, cell.content.toString, pos))
  }.map(_.flatten)

  def currentSymbols(): IO[List[ResultValue]] = notebookContext.get.map(_.allResultValues)

  def currentTasks(): IO[List[TaskInfo]] = taskManager.allTasks

  def idle(): IO[Boolean] = taskManager.runningTasks.map(_.isEmpty)

  // TODO: need to do this in SharedNotebook now
//  def init(): IO[Unit] = getNotebook().flatMap {
//    notebook => if (notebook.cells.isEmpty) IO.unit else {
//      notebook.cells.map {
//        cell => CellContext(cell.id).flatMap(context => IO(notebookContext.insertLast(context)))
//      }.sequence.as(())
//    }
//  }
  def init(): IO[Unit] = IO.unit

  def shutdown(): IO[Unit] = taskManager.shutdown()

  def info: IO[Option[KernelInfo]] = IO.pure(
    // TODO: This should really be in the ServerHandshake as it isn't a Kernel-level thing...
    Option(KernelInfo(
      "Polynote Version:" -> s"""<span id="version">${BuildInfo.version}</span>""",
      "Build Commit:"            -> s"""<span id="commit">${BuildInfo.commit}</span>"""
    ))
  )

  override def getHandleData(handleType: HandleType, handleId: Int, count: Int): IO[Array[ByteVector32]] = handleType match {
    case Lazy =>
      for {
        handleOpt <- IO(LazyDataRepr.getHandle(handleId))
        handle    <- IO.fromEither(Either.fromOption(handleOpt, new NoSuchElementException(s"Lazy#$handleId")))
      } yield Array(ByteVector32(ByteVector(handle.data.rewind().asInstanceOf[ByteBuffer])))

    case Updating =>
      for {
        handleOpt <- IO(UpdatingDataRepr.getHandle(handleId))
        handle    <- IO.fromEither(Either.fromOption(handleOpt, new NoSuchElementException(s"Updating#$handleId")))
      } yield handle.lastData.map(buf => ByteVector32(ByteVector(buf.rewind().asInstanceOf[ByteBuffer]))).toArray

    case Streaming => IO.raiseError(new IllegalStateException("Streaming data is managed on a per-subscriber basis"))
  }

  override def releaseHandle(handleType: HandleType, handleId: Int): IO[Unit] = handleType match {
    case Lazy => IO(LazyDataRepr.releaseHandle(handleId))
    case Updating => IO(UpdatingDataRepr.releaseHandle(handleId))
    case Streaming => IO(StreamingDataRepr.releaseHandle(handleId))
  }

  override def modifyStream(handleId: Int, ops: List[TableOp]): IO[Option[StreamingDataRepr]] = {
    IO(StreamingDataRepr.getHandle(handleId)).flatMap {
      case None => IO.pure(None)
      case Some(handle) => IO.fromEither(handle.modify(ops)).map(StreamingDataRepr.fromHandle).map(Some(_))
    }
  }

  override def cancelTasks(): IO[Unit] = {
    // don't bother to cancel the running fiber – it doesn't seem to do anything except silently succeed
    // instead the language interpreter has to participate by using kernelContext.runInterruptible, so we can call
    // kernelContext.interrupt() here.
    // TODO: Why can't it work with fibers? Is there any point in tracking the fibers if they can't be cancelled?
    taskManager.cancelAllQueued *> IO(kernelContext.interrupt())
  }

  // TODO: no longer the kernels responsibility
  //  override def updateNotebook(version: Int, update: NotebookUpdate): IO[Unit] = update match {
  //    case InsertCell(_, _, _, cell, after) => CellContext(cell.id).flatMap {
  //      cellContext => IO(notebookContext.insert(cellContext, Option(after)))
  //    }
  //    case DeleteCell(_, _, _, id) => IO(notebookContext.remove(id))
  //    case _ => IO.unit
  //  }
}

object PolyKernel {

  def defaultBaseSettings: Settings = new Settings()
  def defaultOutputDir: AbstractFile = new VirtualDirectory("(memory)", None)
  def defaultParentClassLoader: ClassLoader = getClass.getClassLoader

  final class EnqueueSome[F[_], A](queue: Queue[F, Option[A]]) extends Enqueue[F, A] {
    def enqueue1(a: A): F[Unit] = queue.enqueue1(Some(a))
    def offer1(a: A): F[Boolean] = queue.offer1(Some(a))
  }

  def apply(
    notebookContext: SignallingRef[IO, NotebookContext],
    dependencies: Map[String, List[(String, File)]],
    availableInterpreters: Map[String, LanguageInterpreter.Factory[IO]],
    statusUpdates: Publish[IO, KernelStatusUpdate],
    extraClassPath: List[File] = Nil,
    baseSettings: Settings = defaultBaseSettings,
    outputDir: AbstractFile = defaultOutputDir,
    parentClassLoader: ClassLoader = defaultParentClassLoader,
    config: PolynoteConfig
  ): PolyKernel = {

    val kernelContext = KernelContext(dependencies, statusUpdates, baseSettings, extraClassPath, outputDir, parentClassLoader)

    new PolyKernel(
      notebookContext,
      kernelContext,
      outputDir,
      dependencies,
      statusUpdates,
      availableInterpreters,
      config
    )
  }
}
