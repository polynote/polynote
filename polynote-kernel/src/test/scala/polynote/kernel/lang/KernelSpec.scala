package polynote.kernel.lang

import java.util.concurrent.Executors

import cats.effect.internals.IOContextShift
import cats.effect.{ContextShift, IO}
import cats.syntax.apply._
import fs2.concurrent.Topic
import polynote.kernel.context.{GlobalInfo, RuntimeContext}
import polynote.kernel.lang.python.PythonInterpreter
import polynote.kernel.lang.scal.ScalaInterpreter
import polynote.kernel.{KernelStatusUpdate, Result, UpdatedTasks}

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.tools.nsc.Settings

// TODO: make PythonInterpreterSpec a KernelSpec once it gets merged in
trait KernelSpec {
  val settings = new Settings()
  settings.classpath.append(System.getProperty("java.class.path"))
  settings.YpresentationAnyThread.value = true

  implicit val contextShift: ContextShift[IO] = IOContextShift(ExecutionContext.fromExecutorService(Executors.newCachedThreadPool()))

  def assertPythonOutput(code: String)(assertion: (Map[String, Any], Seq[Result], Seq[(String, String)]) => Unit): Unit = {
    assertOutput((globalInfo: GlobalInfo) => PythonInterpreter.factory()(Nil, globalInfo), code)(assertion)
  }

  def assertScalaOutput(code: String)(assertion: (Map[String, Any], Seq[Result], Seq[(String, String)]) => Unit): Unit = {
    assertOutput((globalInfo: GlobalInfo) => ScalaInterpreter.factory()(Nil, globalInfo), code)(assertion)
  }

  def assertOutput[K <: LanguageKernel[IO, GlobalInfo]](mkInterp: GlobalInfo => K, code: String)(assertion: (Map[String, Any], Seq[Result], Seq[(String, String)]) => Unit): Unit =
    assertOutputWith(mkInterp, code) {
      (_, vars, output, displayed) => IO(assertion(vars, output, displayed))
    }

  // TODO: for unit tests we'd ideally want to hook directly to runCode without needing all this!
  def assertOutputWith[K <: LanguageKernel[IO, GlobalInfo]](mkInterp: GlobalInfo => K, code: String)(assertion: (K, Map[String, Any], Seq[Result], Seq[(String, String)]) => IO[Unit]): Unit = {
    Topic[IO, KernelStatusUpdate](UpdatedTasks(Nil)).flatMap { updates =>
      val globalInfo = GlobalInfo(Map.empty, Nil)
      val interp = mkInterp(globalInfo)

      val displayed = mutable.ArrayBuffer.empty[(String, String)]
      polynote.runtime.Runtime.setDisplayer((mimeType, input) => displayed.append((mimeType, input)))

      // TODO: we should get the results of out as well so we can capture output (or maybe interpreters shouldn't even be writing to out!!)
      for {
        // TODO: ideally we wouldn't need to run predef specially
        runResults <- interp.runCode("test", RuntimeContext.getPredefContext(globalInfo), code)
        (results, newCtx) = runResults
        ctx <- newCtx
        namedVars = ctx.symbols.mapValues(_.value) ++ ctx.maybeResult.map(x => "restest" -> x.value) // TODO handle this separately
        output <- results.compile.toVector
      } yield {
        assertion(interp, namedVars, output, displayed)
      }.guarantee{
        IO(polynote.runtime.Runtime.clear()) *> interp.shutdown()
      }
    }.unsafeRunSync()
  }
}
