package polynote.kernel.lang

import java.io.{BufferedReader, InputStreamReader, PrintWriter}
import java.util.concurrent.Executors

import cats.effect.internals.IOContextShift
import cats.effect.{ContextShift, IO}
import cats.instances.vector._
import cats.instances.either._
import cats.syntax.apply._
import cats.syntax.alternative._
import cats.syntax.either._
import fs2.Stream
import fs2.concurrent.{Queue, Topic}
import polynote.kernel.PolyKernel.EnqueueSome
import polynote.kernel.lang.python.PythonInterpreter
import polynote.kernel.lang.scal.ScalaInterpreter
import polynote.kernel.util._
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

  def assertPythonOutput(code: String)(assertion: (Map[String, Any], Seq[Result], Seq[(String, String)]) => Unit): Unit = {
    assertOutputWith((kernelContext: KernelContext) => PythonInterpreter.factory()(Nil, kernelContext), code) {
      (interp, vars, output, displayed) => interp.withJep(assertion(vars, output, displayed))
    }
  }

  def assertScalaOutput(code: String)(assertion: (Map[String, Any], Seq[Result], Seq[(String, String)]) => Unit): Unit = {
    assertOutput((kernelContext: KernelContext) => ScalaInterpreter.factory()(Nil, kernelContext), code)(assertion)
  }

  def assertOutput[K <: LanguageInterpreter[IO]](mkInterp: KernelContext => K, code: String)(assertion: (Map[String, Any], Seq[Result], Seq[(String, String)]) => Unit): Unit =
    assertOutputWith(mkInterp, code) {
      (_, vars, output, displayed) => IO(assertion(vars, output, displayed))
    }

  // TODO: for unit tests we'd ideally want to hook directly to runCode without needing all this!
  def assertOutputWith[K <: LanguageInterpreter[IO]](mkInterp: KernelContext => K, code: String)(assertion: (K, Map[String, Any], Seq[Result], Seq[(String, String)]) => IO[Unit]): Unit = {
    Topic[IO, KernelStatusUpdate](UpdatedTasks(Nil)).flatMap { updates =>
      val kernelContext = KernelContext.default(Map.empty, updates, Nil)
      val interp = mkInterp(kernelContext)
      val displayed = mutable.ArrayBuffer.empty[(String, String)]
      polynote.runtime.Runtime.setDisplayer((mimeType, input) => displayed.append((mimeType, input)))

      // TODO: we should get the results of out as well so we can capture output (or maybe interpreters shouldn't even be writing to out!!)
      interp.init().bracket { _ =>
        CellContext((-1).toShort, None).flatMap {
          predefContext =>

          val done = ReadySignal()

          def runPredef = interp.predefCode.map {
            predefCode => interp.runCode(predefContext, predefCode)
          }.getOrElse(IO.pure(Stream.empty))


          for {
            // publishes to symbol table as a side-effect
            // TODO: ideally we wouldn't need to run predef specially
            predefResults <- runPredef.flatMap(_.compile.toVector)
            cellContext <- CellContext(0.toShort, Some(predefContext))
            results <- interp.runCode(cellContext, code).flatMap(_.compile.toVector)
            output  = predefResults ++ results
            // make  sure everything has been processed
            _       <- done.complete
            (vars, outputs) = output.map {
              case ResultValue(name, _, _, _, value, _, _) => Either.left(name -> value)
              case result => Either.right(result)
            }.separate
            _       <- assertion(interp, vars.toMap, outputs, displayed) *> IO(polynote.runtime.Runtime.clear())
          } yield ()
        }
      }(_ => interp.shutdown())
    }.unsafeRunSync()
  }
}
