package polynote.kernel.lang

import java.io.{BufferedReader, InputStreamReader, PrintWriter}
import java.util.concurrent.Executors

import cats.effect.internals.IOContextShift
import cats.effect.{ContextShift, IO}
import cats.syntax.apply._
import fs2.concurrent.{Queue, Topic}
import polynote.kernel.PolyKernel.EnqueueSome
import polynote.kernel.lang.python.PythonInterpreter
import polynote.kernel.lang.scal.ScalaInterpreter
import polynote.kernel.util.{KernelContext, KernelReporter, ReadySignal, RuntimeSymbolTable}
import polynote.kernel._

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.tools.nsc.Settings
import scala.tools.nsc.interactive.Global
import scala.tools.nsc.reporters.ConsoleReporter

// TODO: make PythonInterpreterSpec a KernelSpec once it gets merged in
trait KernelSpec {
  val settings = new Settings()
  settings.classpath.append(System.getProperty("java.class.path"))
  settings.YpresentationAnyThread.value = true

  implicit val contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.fromExecutorService(Executors.newCachedThreadPool()))

  // TODO: sharing kernelContext across all tests is a terrible way to approximate running multiple cells of a notebook...
  val kernelContext = KernelContext(Map.empty, Nil)

  def assertPythonOutput(code: String)(assertion: (Map[String, Any], Seq[Result], Seq[(String, String)]) => Unit): Unit = {
    assertOutputWith((rst: RuntimeSymbolTable) => PythonInterpreter.factory()(Nil, rst), code) {
      (interp, vars, output, displayed) => interp.withJep(assertion(vars, output, displayed))
    }
  }

  def assertScalaOutput(code: String)(assertion: (Map[String, Any], Seq[Result], Seq[(String, String)]) => Unit): Unit = {
    assertOutput((rst: RuntimeSymbolTable) => ScalaInterpreter.factory()(Nil, rst), code)(assertion)
  }

  def assertOutput[K <: LanguageInterpreter[IO]](mkInterp: RuntimeSymbolTable => K, code: String)(assertion: (Map[String, Any], Seq[Result], Seq[(String, String)]) => Unit): Unit =
    assertOutputWith(mkInterp, code) {
      (_, vars, output, displayed) => IO(assertion(vars, output, displayed))
    }

  // TODO: for unit tests we'd ideally want to hook directly to runCode without needing all this!
  def assertOutputWith[K <: LanguageInterpreter[IO]](mkInterp: RuntimeSymbolTable => K, code: String)(assertion: (K, Map[String, Any], Seq[Result], Seq[(String, String)]) => IO[Unit]): Unit = {
    Topic[IO, KernelStatusUpdate](UpdatedTasks(Nil)).flatMap { updates =>
      val symbolTable = new RuntimeSymbolTable(kernelContext, updates)
      val interp = mkInterp(symbolTable)
      val displayed = mutable.ArrayBuffer.empty[(String, String)]
      polynote.runtime.Runtime.setDisplayer((mimeType, input) => displayed.append((mimeType, input)))

      // TODO: we should get the results of out as well so we can capture output (or maybe interpreters shouldn't even be writing to out!!)
      interp.init().bracket { _ =>
      // without this, symbolTable.drain() doesn't do anything
      symbolTable.subscribe()(_ => IO.unit)
      val done = ReadySignal()

      for {
        // publishes to symbol table as a side-effect
        // TODO: ideally we wouldn't need to run predef specially
        symsF   <- symbolTable.subscribe()(_ => IO.unit).map(_._1).interruptWhen(done()).compile.toVector.start
        results <- interp.runCode("test", symbolTable.currentTerms.map(_.asInstanceOf[interp.Decl]), Nil, code)
        output  <- results.evalMap {
            case v: ResultValue =>
              symbolTable.publishAll(symbolTable.RuntimeValue.fromResultValue(v, interp).toList).map { _ =>
                None
              }
            case result =>
              IO.pure(result).map(Option(_))
          }.unNone.compile.toVector
        // make  sure everything has been processed
        _       <- symbolTable.drain()
        _       <- done.complete
        _       <- symsF.join
        namedVars = symbolTable.currentTerms.map(v => v.name.toString -> v.value).toMap
        _       <- assertion(interp, namedVars, output, displayed) *> IO(polynote.runtime.Runtime.clear())
      } yield ()
      }(_ => interp.shutdown())
    }.unsafeRunSync()
  }
}
