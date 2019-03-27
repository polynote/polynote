package polynote.kernel

import java.io.File
import java.net.URL
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.util.Date
import java.util.concurrent.{ConcurrentHashMap, Executors}

import cats.effect.concurrent.{Ref, Semaphore}
import cats.effect.internals.IOContextShift
import cats.effect.{Clock, ContextShift, IO}
import cats.syntax.apply._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import cats.instances.list._
import fs2.Stream
import fs2.concurrent.{Enqueue, Queue, SignallingRef, Topic}
import org.log4s.{Logger, getLogger}
import polynote.buildinfo.BuildInfo
import polynote.config.PolynoteConfig
import polynote.kernel.PolyKernel.EnqueueSome
import polynote.kernel.lang.LanguageInterpreter
import polynote.kernel.util.KernelContext
import polynote.kernel.util.{RuntimeSymbolTable, _}
import polynote.messages._
import polynote.runtime.{LazyDataRepr, StreamingDataRepr, TableOp, UpdatingDataRepr}
import scodec.bits.ByteVector

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.MILLISECONDS
import scala.reflect.internal.util.ScalaClassLoader.URLClassLoader
import scala.reflect.internal.util.{AbstractFileClassLoader, BatchSourceFile}
import scala.reflect.io.{AbstractFile, VirtualDirectory}
import scala.tools.nsc.Settings
import scala.tools.nsc.interactive.Global

class PolyKernel private[kernel] (
  private val getNotebook: () => IO[Notebook],
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
    * The symbol table, which stores all the currently existing runtime values
    */
  protected lazy val symbolTable = new RuntimeSymbolTable(kernelContext, statusUpdates)

  /**
    * The task queue, which tracks currently running and queued kernel tasks (i.e. cells to run)
    */
  protected lazy val taskManager: TaskManager = TaskManager(statusUpdates).unsafeRunSync()

  // TODO: duplicates logic from runCell
  private def runPredef(interp: LanguageInterpreter[IO], language: String): IO[Stream[IO, Result]] =
    interp.init() *> {
      interp.predefCode match {
        case Some(code) => taskManager.runTaskIO(s"Predef $language", s"Predef ($language)")(_ => interp.runCode(-1, Nil, Nil, code)).map {
          results => results.collect {
            case v: ResultValue => v
          }.evalTap {
            v => symbolTable.publishAll(symbolTable.RuntimeValue.fromResultValue(v, interp).toList)
          }
        }

        case None => IO.pure(Stream.empty)
      }
    }

  protected def getInterpreter(language: String): Option[LanguageInterpreter[IO]] = Option(interpreters.get(language))

  protected def getOrLaunchInterpreter(language: String): IO[(LanguageInterpreter[IO], Stream[IO, Result])] =
    getInterpreter(language)
      .map(interp => IO.pure(interp -> Stream.empty))
      .getOrElse {
        launchingInterpreter.acquire.bracket { _ =>
          Option(interpreters.get(language)).map(interp => IO.pure(interp -> Stream.empty)).getOrElse {
            for {
              factory <- IO.fromEither(Either.fromOption(availableInterpreters.get(language), new RuntimeException(s"No interpreter for language $language")))
              interp  <- taskManager.runTask(s"Interpreter$$$language", s"Starting $language interpreter")(_ => factory.apply(dependencies.getOrElse(language, Nil), symbolTable))
              _        = interpreters.put(language, interp)
              results  <- runPredef(interp, language)
            } yield (interp, results)
          }
        } {
          _ => launchingInterpreter.release
        }
  }

  /**
    * Perform a task upon a notebook cell, if the interpreter for that cell has already been launched. If the interpreter
    * hasn't been launched, the action won't be performed and the result will be None.
    */
  protected def withLaunchedInterpreter[A](cellId: CellID)(fn: (Notebook, NotebookCell, LanguageInterpreter[IO]) => IO[A]): IO[Option[A]] = for {
    notebook <- getNotebook()
    cell     <- IO(notebook.cell(cellId))
    interp    = getInterpreter(cell.language)
    result   <- interp.fold(IO.pure(Option.empty[A]))(interp => fn(notebook, cell, interp).map(result => Option(result)))
  } yield result

  /**
    * Perform a task upon a notebook cell, launching the interpreter if necessary. Since this may result in the interpreter's
    * predef code being executed, and that might produce results, the caller must be prepared to handle the result stream
    * (which is passed into the given task)
    */
  protected def withInterpreter[A](cellId: CellID)(fn: (Notebook, NotebookCell, LanguageInterpreter[IO], Stream[IO, Result]) => IO[A])(ifText: => IO[A]): IO[A] = {
    def launchLanguageAndRun(notebook: Notebook, cell: NotebookCell) =
      if (cell.language != "text") {
        for {
          launch   <- getOrLaunchInterpreter(cell.language)
          (interp, predefResults) = launch
          result   <- fn(notebook, cell, interp, predefResults)
        } yield result
      } else ifText

    for {
      notebook <- getNotebook()
      cell     <- IO(notebook.cell(cellId))
      result   <- launchLanguageAndRun(notebook, cell)
    } yield result
  }

  // TODO: using -1 for Predef is kinda weird.
  private def prevCells(notebook: Notebook, id: CellID): List[CellID] = List[CellID](-1) ++ notebook.cells.view.takeWhile(_.id != id).map(_.id).toList

  protected def findAvailableSymbols(prevCells: List[CellID], interp: LanguageInterpreter[IO]): Seq[interp.Decl] = {
    symbolTable.currentTerms.filter(v => prevCells.contains(v.sourceCellId)).asInstanceOf[Seq[interp.Decl]]
  }

  def startInterpreterFor(cellId: CellID): IO[Stream[IO, Result]] = withInterpreter(cellId) {
    (_, _, _, results) => IO.pure(results)
  } (IO.pure(Stream.empty))

  def runCell(id: CellID): IO[Stream[IO, Result]] = queueCell(id).flatten

  def queueCell(id: CellID): IO[IO[Stream[IO, Result]]] = {
    taskManager.queueTaskStream(s"Cell $id", s"Cell $id", s"Running $id") {
      taskInfo =>
        Queue.unbounded[IO, Option[Result]].map {
          oq =>
            val oqSome = new EnqueueSome(oq)
            val run = withLaunchedInterpreter(id) {
              (notebook, cell, interp) =>
                val prevCellIds = prevCells(notebook, id)
                // TODO: should this be something the interpreter has to do? Without this we wouldn't even need to allocate a queue here
                polynote.runtime.Runtime.setDisplayer((mime, content) => oqSome.enqueue1(Output(mime, content)).unsafeRunSync())
                polynote.runtime.Runtime.setProgressSetter {
                  (progress, detail) =>
                    val newDetail = Option(detail).getOrElse(taskInfo.detail)
                    statusUpdates.publish1(UpdatedTasks(taskInfo.copy(detail = newDetail, progress = (progress * 255).toByte) :: Nil)).unsafeRunSync()
                }

                val results = interp.runCode(
                  id,
                  findAvailableSymbols(prevCellIds, interp),
                  prevCellIds,
                  cell.content.toString
                ).map {
                  results => results.evalMap {
                    case v: ResultValue =>
                      symbolTable.publishAll(symbolTable.RuntimeValue.fromResultValue(v, interp).toList).as(v)
                    case result =>
                      IO.pure(result)
                  }
                }

                for {
                  _ <- symbolTable.drain()
                  start <- clock.monotonic(MILLISECONDS)
                  res <- results
                } yield {
                  res.onComplete {
                    Stream.eval {
                      for {
                        end <- clock.monotonic(MILLISECONDS)
                        timestamp <- clock.realTime(MILLISECONDS)
                      } yield ExecutionInfo((end - start).toInt, timestamp)
                    }
                  }
                }
            }.map {
              _.getOrElse(Stream.empty)
            }.handleErrorWith(ErrorResult.toStream).map {
              _ ++ Stream.eval(oq.enqueue1(None)).drain ++ Stream.eval(symbolTable.drain()).drain
            }

            Stream.emit(ClearResults()) ++ Stream(oq.dequeue.unNoneTerminate, Stream.eval(run).flatten).parJoinUnbounded
      }
    }
  }

  def runCells(ids: List[CellID]): IO[Stream[IO, CellResult]] = getNotebook().map {
    notebook => Stream.emits(ids).evalMap {
      id => runCell(id).map(results => results.map(result => CellResult(notebook.path, id, result)))
    }.flatten // TODO: better control execution order of tasks, and use parJoinUnbounded instead
  }

  def completionsAt(id: CellID, pos: Int): IO[List[Completion]] = withLaunchedInterpreter(id) {
    (notebook, cell, interp) =>
      val prevCellIds = prevCells(notebook, id)
      interp.completionsAt(id, findAvailableSymbols(prevCellIds, interp), prevCellIds, cell.content.toString, pos)
  }.map(_.getOrElse(Nil))

  def parametersAt(id: CellID, pos: Int): IO[Option[Signatures]] = withLaunchedInterpreter(id) {
    (notebook, cell, interp) =>
      val prevCellIds = prevCells(notebook, id)
      interp.parametersAt(id, findAvailableSymbols(prevCellIds, interp), prevCellIds, cell.content.toString, pos)
  }.map(_.flatten)

  def currentSymbols(): IO[List[ResultValue]] = IO {
    symbolTable.currentTerms.toList.map(_.toResultValue)
  }

  def currentTasks(): IO[List[TaskInfo]] = taskManager.allTasks

  def idle(): IO[Boolean] = taskManager.runningTasks.map(_.isEmpty)

  def init: IO[Unit] = IO.unit

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
    // TODO: have to go through symbolTable.kernelContext because SparkPolyKernel overrides it (but not kernelContext) - needs to be fixed
    taskManager.cancelAllQueued *> IO(symbolTable.kernelContext.interrupt())
  }

  override def updateNotebook(version: Int, update: NotebookUpdate): IO[Unit] = IO.unit
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
    getNotebook: () => IO[Notebook],
    dependencies: Map[String, List[(String, File)]],
    availableInterpreters: Map[String, LanguageInterpreter.Factory[IO]],
    statusUpdates: Publish[IO, KernelStatusUpdate],
    extraClassPath: List[File] = Nil,
    baseSettings: Settings = defaultBaseSettings,
    outputDir: AbstractFile = defaultOutputDir,
    parentClassLoader: ClassLoader = defaultParentClassLoader,
    config: PolynoteConfig
  ): PolyKernel = {

    val kernelContext = KernelContext(dependencies, baseSettings, extraClassPath, outputDir, parentClassLoader)

    new PolyKernel(
      getNotebook,
      kernelContext,
      outputDir,
      dependencies,
      statusUpdates,
      availableInterpreters,
      config
    )
  }
}
