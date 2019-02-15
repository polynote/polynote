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
import fs2.concurrent.{Enqueue, Queue, Topic}
import org.log4s.{Logger, getLogger}
import polynote.kernel.PolyKernel.EnqueueSome
import polynote.kernel.lang.LanguageKernel
import polynote.kernel.util.GlobalInfo
import polynote.kernel.util.{RuntimeSymbolTable, _}
import polynote.messages.{Notebook, NotebookCell}

import scala.concurrent.ExecutionContext
import scala.reflect.internal.util.ScalaClassLoader.URLClassLoader
import scala.reflect.internal.util.{AbstractFileClassLoader, BatchSourceFile}
import scala.reflect.io.{AbstractFile, VirtualDirectory}
import scala.tools.nsc.Settings
import scala.tools.nsc.interactive.Global

class PolyKernel private[kernel] (
  private val getNotebook: () => IO[Notebook],
  val globalInfo: GlobalInfo,
  val outputDir: AbstractFile,
  dependencies: Map[String, List[(String, File)]],
  val statusUpdates: Publish[IO, KernelStatusUpdate],
  subKernels: Map[String, LanguageKernel.Factory[IO]] = Map.empty
) extends Kernel[IO] {

  protected val logger: Logger = getLogger

  protected implicit val contextShift: ContextShift[IO] = IOContextShift(ExecutionContext.fromExecutorService(Executors.newCachedThreadPool()))

  private val launchingKernel = Semaphore[IO](1).unsafeRunSync()
  private val kernels = new ConcurrentHashMap[String, LanguageKernel[IO]]()


  /**
    * The symbol table, which stores all the currently existing runtime values
    */
  protected lazy val symbolTable = new RuntimeSymbolTable(globalInfo, statusUpdates)

  /**
    * The task queue, which tracks currently running and queued kernel tasks (i.e. cells to run)
    */
  protected lazy val taskQueue: TaskQueue = TaskQueue(statusUpdates).unsafeRunSync()

  private def runPredef(kernel: LanguageKernel[IO], language: String): IO[Unit] =
    kernel.predefCode.fold(kernel.init()) {
      code => for {
        oq <- Queue.unbounded[IO, Result]
        _  <- taskQueue.runTaskIO(s"Predef $language", s"Predef ($language)")(_ => kernel.runCode("Predef", Nil, Nil, code, oq, statusUpdates))
      } yield ()
    }.handleErrorWith {
      err =>
        IO(logger.error(err)(err.getMessage)) *> IO.raiseError(err)
    }

  protected def getKernel(language: String): IO[LanguageKernel[IO]] = Option(kernels.get(language)).map(IO.pure).getOrElse {
    launchingKernel.acquire.bracket { _ =>
      Option(kernels.get(language)).map(IO.pure).getOrElse {
        for {
          factory <- IO.fromEither(Either.fromOption(subKernels.get(language), new RuntimeException(s"No kernel for language $language")))
          kernel  <- taskQueue.runTask(s"Kernel$$$language", s"Starting $language kernel")(_ => factory.apply(dependencies.getOrElse(language, Nil), symbolTable))
          _        = kernels.put(language, kernel)
          _       <- runPredef(kernel, language)
        } yield kernel
      }
    } {
      _ => launchingKernel.release
    }
  }

  protected def withKernel[A](cellId: String)(fn: (Notebook, NotebookCell, LanguageKernel[IO]) => A): IO[A] = for {
    notebook <- getNotebook()
    cell     <- IO.fromEither(Either.fromOption(notebook.cells.find(_.id == cellId), new NoSuchElementException(s"Cell $cellId does not exist")))
    kernel   <- getKernel(cell.language)
  } yield fn(notebook, cell, kernel)

  private def prevCells(notebook: Notebook, id: String) = "Predef" :: notebook.cells.view.takeWhile(_.id != id).map(_.id).toList

  protected def findAvailableSymbols(prevCells: List[String], kernel: LanguageKernel[IO]): Seq[kernel.Decl] = {
    symbolTable.currentTerms.filter(v => prevCells.contains(v.sourceCellId)).asInstanceOf[Seq[kernel.Decl]]
  }

  def runCell(id: String): IO[fs2.Stream[IO, Result]] = {
    val done = ReadySignal()
    symbolTable.drain() *> Queue.unbounded[IO, Option[Result]].flatMap {
      oq =>
          val oqSome = new EnqueueSome(oq)
          oq.enqueue1(Some(ClearResults())) *> withKernel(id) {
            (notebook, cell, kernel) =>
              val prevCellIds = prevCells(notebook, id)
              taskQueue.runTaskIO(id, id, s"Running $id") {
                taskInfo =>
                  polynote.runtime.Runtime.setDisplayer((mime, content) => oqSome.enqueue1(Output(mime, content)).unsafeRunSync())
                  polynote.runtime.Runtime.setProgressSetter {
                    (progress, detail) =>
                      val newDetail = Option(detail).getOrElse(taskInfo.detail)
                      statusUpdates.publish1(UpdatedTasks(taskInfo.copy(detail = newDetail, progress = (progress * 255).toByte) :: Nil)).unsafeRunSync()
                  }
                  kernel.runCode(
                    id,
                    findAvailableSymbols(prevCellIds, kernel),
                    prevCellIds,
                    cell.content.toString,
                    oqSome,
                    statusUpdates
                  ).handleErrorWith {
                    case errs@CompileErrors(_) =>
                      oqSome.enqueue1(errs)
                    case err@RuntimeError(_) =>
                      oqSome.enqueue1(err)
                    case err =>
                      oqSome.enqueue1(RuntimeError(err))
                  }.guarantee(oq.enqueue1(None)) *> symbolTable.drain()
              }
        }.flatten.uncancelable.start.map {
          fiber => oq.dequeue.unNoneTerminate
        }
    }
  }

  def completionsAt(id: String, pos: Int): IO[List[Completion]] = withKernel(id) {
    (notebook, cell, kernel) =>
      val prevCellIds = prevCells(notebook, id)
      kernel.completionsAt(id, findAvailableSymbols(prevCellIds, kernel), prevCellIds, cell.content.toString, pos)
  }.flatMap(identity)

  def parametersAt(id: String, pos: Int): IO[Option[Signatures]] = withKernel(id) {
    (notebook, cell, kernel) =>
      val prevCellIds = prevCells(notebook, id)
      kernel.parametersAt(id, findAvailableSymbols(prevCellIds, kernel), prevCellIds, cell.content.toString, pos)
  }.flatMap(identity)

  def currentSymbols(): IO[List[SymbolInfo]] = IO {
    symbolTable.currentTerms.toList.map {
      case v @ symbolTable.RuntimeValue(name, value, scalaType, _, _) => SymbolInfo(
        name.decodedName.toString,
        v.typeString,
        v.valueString,
        Nil
      )
    }
  }

  def currentTasks(): IO[List[TaskInfo]] = taskQueue.allTasks

  def idle(): IO[Boolean] = taskQueue.currentTask.map(_.isEmpty)

  def init: IO[Unit] = IO.unit

  def shutdown(): IO[Unit] = IO.unit

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
    subKernels: Map[String, LanguageKernel.Factory[IO]],
    statusUpdates: Publish[IO, KernelStatusUpdate],
    extraClassPath: List[File] = Nil,
    baseSettings: Settings = defaultBaseSettings,
    outputDir: AbstractFile = defaultOutputDir,
    parentClassLoader: ClassLoader = defaultParentClassLoader
  ): PolyKernel = {

    val globalInfo = GlobalInfo(dependencies, baseSettings, extraClassPath, outputDir, parentClassLoader)

    new PolyKernel(
      getNotebook,
      globalInfo,
      outputDir,
      dependencies,
      statusUpdates,
      subKernels
    )
  }
}
