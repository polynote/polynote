package polynote.kernel.util

import cats.data.Ior
import polynote.kernel.{CompileErrors, KernelReport, Pos}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.reflect.internal.util.Position
import scala.tools.nsc.Settings
import scala.tools.nsc.reporters.FilteringReporter

/**
  * This separate source file is necessary, because the interface for Reporter changed in Scala 2.13
  */
case class KernelReporter(settings: Settings) extends FilteringReporter {

  private val _reports = new ListBuffer[KernelReport]()

  def display(pos: Position, msg: String, severity: Severity): Unit = _reports.synchronized {
    _reports += KernelReport(new Pos(pos), msg, severity.id)
  }

  override def doReport(pos: Position, msg: String, severity: Severity): Unit =  _reports.synchronized {
    _reports += KernelReport(new Pos(pos), msg, severity.id)
  }

  def displayPrompt(): Unit = ()

  override def reset(): Unit = {
    super.reset()
    _reports.clear()
  }

  def reports: List[KernelReport] = _reports.synchronized(_reports.toList)

  private def captureState = State(reports, 0, warningCount, errorCount)
  private def restoreState(state: State): Unit = {
    reset()
    _reports ++= state.reports
    (0 until state.warns).foreach(_ => increment(WARNING))
    (0 until state.errs).foreach(_ => increment(ERROR))
  }

  def attempt[T](fn: => T): Either[Throwable, T] = _reports.synchronized {
    val state = captureState
    reset()

    try {
      val result = Right(fn)

      if (hasErrors)
        throw CompileErrors(_reports.filter(_.severity == ERROR.id).toList)

      result
    } catch {
      case err: Throwable =>
        Left(err)
    } finally {
      restoreState(state)
    }
  }

  def attemptIor[T](fn: => T): Ior[Throwable, T] = _reports.synchronized {
    val state = captureState
    reset()

    try {
      val result = Ior.right(fn)

      if (hasErrors)
        result.putLeft(CompileErrors(_reports.filter(_.severity == ERROR.id).toList))
      else
        result

    } catch {
      case err: Throwable =>
        Ior.Left(err)
    } finally {
      restoreState(state)
    }
  }

  private case class State(reports: List[KernelReport], infos: Int, warns: Int, errs: Int)
}
