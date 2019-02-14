package polynote.kernel.lang

import java.io.{BufferedReader, InputStreamReader, PrintWriter}
import java.util.concurrent.Executors

import cats.effect.internals.IOContextShift
import cats.effect.{ContextShift, IO}
import cats.syntax.apply._
import fs2.concurrent.{Queue, Topic}
import polynote.kernel.PolyKernel.EnqueueSome
import polynote.kernel.context.{GlobalInfo, RuntimeContext}
import polynote.kernel.lang.python.PythonInterpreter
import polynote.kernel.lang.scal.ScalaInterpreter
import polynote.kernel.util.{KernelReporter, RuntimeSymbolTable, ReadySignal}
import polynote.kernel.{KernelStatusUpdate, PolyKernel, Result, UpdatedTasks}

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

  implicit val contextShift: ContextShift[IO] = IOContextShift(ExecutionContext.fromExecutorService(Executors.newCachedThreadPool()))

  def assertPythonOutput(code: String)(assertion: (Map[String, Any], Seq[Result], Seq[(String, String)]) => Unit): Unit = {
    assertOutputWith((ctx: RuntimeContext) => PythonInterpreter.factory()(Nil, ctx), code) {
      (interp, vars, output, displayed) => interp.withJep(assertion(vars, output, displayed))
    }
  }

  def assertScalaOutput(code: String)(assertion: (Map[String, Any], Seq[Result], Seq[(String, String)]) => Unit): Unit = {
    assertOutput((ctx: RuntimeContext) => ScalaInterpreter.factory()(Nil, ctx), code)(assertion)
  }

  def assertOutput[K <: LanguageKernel[IO]](mkInterp: RuntimeContext => K, code: String)(assertion: (Map[String, Any], Seq[Result], Seq[(String, String)]) => Unit): Unit =
    assertOutputWith(mkInterp, code) {
      (_, vars, output, displayed) => IO(assertion(vars, output, displayed))
    }

  // TODO: for unit tests we'd ideally want to hook directly to runCode without needing all this!
  def assertOutputWith[K <: LanguageKernel[IO]](mkInterp: RuntimeContext => K, code: String)(assertion: (K, Map[String, Any], Seq[Result], Seq[(String, String)]) => IO[Unit]): Unit = {
    Topic[IO, KernelStatusUpdate](UpdatedTasks(Nil)).flatMap { updates =>
      val runtimeCtx = new RuntimeContext(GlobalInfo(Map.empty, Nil))
      val interp = mkInterp(runtimeCtx)

      val displayed = mutable.ArrayBuffer.empty[(String, String)]
      polynote.runtime.Runtime.setDisplayer((mimeType, input) => displayed.append((mimeType, input)))

      // TODO: we should get the results of out as well so we can capture output (or maybe interpreters shouldn't even be writing to out!!)
      for {
        // publishes to symbol table as a side-effect
        // TODO: ideally we wouldn't need to run predef specially
        runResults <- interp.runCode("test", interp.runtimeContext.getContextFor("$Predef"), code)
        (results, newCtx) = runResults
        namedVars = newCtx.symbols.mapValues(_.value) ++ newCtx.maybeResult.map(x => "restest" -> x.value) // TODO handle this separately
        output <- results.compile.toVector
      } yield {
        assertion(interp, namedVars, output, displayed)
      }.guarantee{
        IO(polynote.runtime.Runtime.clear()) *> interp.shutdown()
      }
    }.unsafeRunSync()
  }
}
