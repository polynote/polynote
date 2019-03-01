package polynote.kernel

import java.io.File
import java.net.URL
import java.util.concurrent.{ConcurrentHashMap, Executors}

import cats.effect.concurrent.{Ref, Semaphore}
import cats.effect.internals.IOContextShift
import cats.effect.{ContextShift, IO}
import cats.syntax.apply._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import cats.instances.list._
import fs2.Stream
import fs2.concurrent.{Enqueue, Queue, Topic}
import org.log4s.{Logger, getLogger}
import polynote.kernel.PolyKernel.EnqueueSome
import polynote.kernel.lang.LanguageInterpreter
import polynote.kernel.util.KernelContext
import polynote.kernel.util.{RuntimeSymbolTable, _}
import polynote.messages._

import scala.concurrent.ExecutionContext
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
  availableInterpreters: Map[String, LanguageInterpreter.Factory[IO]] = Map.empty
) extends KernelAPI[IO] {

  protected val logger: Logger = getLogger

  protected implicit val contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.fromExecutorService(Executors.newCachedThreadPool()))

  private val launchingInterpreter = Semaphore[IO](1).unsafeRunSync()
  private val interpreters = new ConcurrentHashMap[String, LanguageInterpreter[IO]]()


  /**
    * The symbol table, which stores all the currently existing runtime values
    */
  protected lazy val symbolTable = new RuntimeSymbolTable(kernelContext, statusUpdates)

  /**
    * The task queue, which tracks currently running and queued kernel tasks (i.e. cells to run)
    */
  protected lazy val taskQueue: TaskQueue = TaskQueue(statusUpdates).unsafeRunSync()

  // TODO: duplicates logic from runCell
  private def runPredef(interp: LanguageInterpreter[IO], language: String): IO[Stream[IO, Result]] =
    interp.init() *> {
      interp.predefCode match {
        case Some(code) => taskQueue.runTaskIO(s"Predef $language", s"Predef ($language)")(_ => interp.runCode(-1, Nil, Nil, code)).map {
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
              interp  <- taskQueue.runTask(s"Interpreter$$$language", s"Starting $language interpreter")(_ => factory.apply(dependencies.getOrElse(language, Nil), symbolTable))
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
  protected def withInterpreter[A](cellId: CellID)(fn: (Notebook, NotebookCell, LanguageInterpreter[IO], Stream[IO, Result]) => IO[A]): IO[A] = for {
    notebook <- getNotebook()
    cell     <- IO(notebook.cell(cellId))
    launch   <- getOrLaunchInterpreter(cell.language)
    (interp, predefResults) = launch
    result   <- fn(notebook, cell, interp, predefResults)
  } yield result

  // TODO: using -1 for Predef is kinda weird.
  private def prevCells(notebook: Notebook, id: CellID): List[CellID] = List[CellID](-1) ++ notebook.cells.view.takeWhile(_.id != id).map(_.id).toList

  protected def findAvailableSymbols(prevCells: List[CellID], interp: LanguageInterpreter[IO]): Seq[interp.Decl] = {
    symbolTable.currentTerms.filter(v => prevCells.contains(v.sourceCellId)).asInstanceOf[Seq[interp.Decl]]
  }

  def startInterpreterFor(cellId: CellID): IO[Stream[IO, Result]] = withInterpreter(cellId) {
    (_, _, _, results) => IO.pure(results)
  }

  def runCell(id: CellID): IO[Stream[IO, Result]] = {
    val done = ReadySignal()
    Queue.unbounded[IO, Option[Result]].map {
      oq =>
          val oqSome = new EnqueueSome(oq)
          val cellResults = Stream.eval {
            withInterpreter(id) {
              (notebook, cell, interp, predefResults) =>
                val prevCellIds = prevCells(notebook, id)
                taskQueue.runTaskIO(s"Cell $id", s"Cell $id", s"Running $id") {
                  taskInfo =>
                    // TODO: should this be something the interpreter has to do? Without this we wouldn't even need to allocate a queue here
                    polynote.runtime.Runtime.setDisplayer((mime, content) => oqSome.enqueue1(Output(mime, content)).unsafeRunSync())
                    polynote.runtime.Runtime.setProgressSetter {
                      (progress, detail) =>
                        val newDetail = Option(detail).getOrElse(taskInfo.detail)
                        statusUpdates.publish1(UpdatedTasks(taskInfo.copy(detail = newDetail, progress = (progress * 255).toByte) :: Nil)).unsafeRunSync()
                    }

                    symbolTable.drain() *> interp.runCode(
                      id,
                      findAvailableSymbols(prevCellIds, interp),
                      prevCellIds,
                      cell.content.toString
                    ).map {
                      results => predefResults merge results.evalMap {
                        case v: ResultValue =>
                          symbolTable.publishAll(symbolTable.RuntimeValue.fromResultValue(v, interp).toList).as(v)
                        case result =>
                          IO.pure(result)
                      }
                    }.handleErrorWith {
                      case errs@CompileErrors(_) => IO.pure(Stream.emit(errs))
                      case err@RuntimeError(_) => IO.pure(Stream.emit(err))
                      case err => IO.pure(Stream.emit(RuntimeError(err)))
                    }.guarantee(oq.enqueue1(None)) <* symbolTable.drain()
                }
            }.handleErrorWith(err => IO.pure(Stream.emit(RuntimeError(err))))
          }
        Stream.emit(ClearResults()) ++ Stream(cellResults.flatten, oq.dequeue.unNoneTerminate).parJoinUnbounded
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

  def currentTasks(): IO[List[TaskInfo]] = taskQueue.allTasks

  def idle(): IO[Boolean] = taskQueue.currentTask.map(_.isEmpty)

  def init: IO[Unit] = IO.unit

  def shutdown(): IO[Unit] = IO.unit

  def info: IO[Option[KernelInfo]] = IO.pure(None)

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
    parentClassLoader: ClassLoader = defaultParentClassLoader
  ): PolyKernel = {

    val kernelContext = KernelContext(dependencies, baseSettings, extraClassPath, outputDir, parentClassLoader)

    new PolyKernel(
      getNotebook,
      kernelContext,
      outputDir,
      dependencies,
      statusUpdates,
      availableInterpreters
    )
  }
}
