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
import polynote.kernel.util.{GlobalInfo, KernelReporter, RuntimeSymbolTable}
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
    assertOutput((rst: RuntimeSymbolTable) => PythonInterpreter.factory()(Nil, rst), code)(assertion)
  }

  def assertScalaOutput(code: String)(assertion: (Map[String, Any], Seq[Result], Seq[(String, String)]) => Unit): Unit = {
    assertOutput((rst: RuntimeSymbolTable) => ScalaInterpreter.factory()(Nil, rst), code)(assertion)
  }

  // TODO: for unit tests we'd ideally want to hook directly to runCode without needing all this!
  def assertOutput(mkInterp: RuntimeSymbolTable => LanguageKernel[IO], code: String)(assertion: (Map[String, Any], Seq[Result], Seq[(String, String)]) => Unit): Unit = {
    Topic[IO, KernelStatusUpdate](UpdatedTasks(Nil)).flatMap { updates =>
      val symbolTable = new RuntimeSymbolTable(GlobalInfo(Map.empty, Nil), updates)
      val interp = mkInterp(symbolTable)
      interp.init().unsafeRunSync()
      val displayed = mutable.ArrayBuffer.empty[(String, String)]
      polynote.runtime.Runtime.setDisplayer((mimeType, input) => displayed.append((mimeType, input)))

      // TODO: we should get the results of out as well so we can capture output (or maybe interpreters shouldn't even be writing to out!!)
      interp.init().bracket { _ =>
        Queue.unbounded[IO, Option[Result]].flatMap {
          out =>
            val oqSome = new EnqueueSome(out)
            Topic[IO, KernelStatusUpdate](UpdatedTasks(Nil)).flatMap {
              statusUpdates =>

                // without this, symbolTable.drain() doesn't do anything
                symbolTable.subscribe()(_ => IO.unit)

                for {
                  // publishes to symbol table as a side-effect
                  // TODO: ideally we wouldn't need to run predef specially
                  _      <- interp.runCode("test", symbolTable.currentTerms.map(_.asInstanceOf[interp.Decl]), Nil, code, oqSome, statusUpdates)

                  // make sure everything has been processed
                  _      <- symbolTable.drain()
                  _      <- out.enqueue1(None) // terminate the queue
                  output <- out.dequeue.unNoneTerminate.compile.toVector
                } yield {
                  // these are the vars runCode published
                  val namedVars = symbolTable.currentTerms.map(v => v.name.toString -> v.value).toMap
                  assertion(namedVars, output, displayed)
                  polynote.runtime.Runtime.clear()}
              }
            }
      }(_ => interp.shutdown())
    }.unsafeRunSync()
  }
}
