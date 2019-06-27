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
    assertPythonOutput(Seq(code))(assertion)
  }

  def assertPythonOutput(code: Seq[String])(assertion: (Map[String, Any], Seq[Result], Seq[(String, String)]) => Unit): Unit = {
    assertOutputWith((kernelContext: KernelContext, _) => PythonInterpreter.factory()(Nil, kernelContext), code) {
      (interp, vars, output, displayed) => interp.withJep(assertion(vars, output, displayed))
    }
  }

  def assertScalaOutput(code: Seq[String])(assertion: (Map[String, Any], Seq[Result], Seq[(String, String)]) => Unit): Unit = {
    assertOutput((kernelContext: KernelContext, _) => ScalaInterpreter.factory()(Nil, kernelContext), code)(assertion)
  }

  def assertScalaOutput(code: String)(assertion: (Map[String, Any], Seq[Result], Seq[(String, String)]) => Unit): Unit = {
    assertScalaOutput(Seq(code))(assertion)
  }

  def assertOutput[K <: LanguageInterpreter[IO]](mkInterp: (KernelContext, Topic[IO, KernelStatusUpdate]) => K, code: Seq[String])(assertion: (Map[String, Any], Seq[Result], Seq[(String, String)]) => Unit): Unit =
    assertOutputWith(mkInterp, code) {
      (_, vars, output, displayed) => IO(assertion(vars, output, displayed))
    }

  def getKernelContext(updates: Topic[IO, KernelStatusUpdate]): KernelContext = KernelContext.default(Map.empty, updates, Nil)

  // TODO: for unit tests we'd ideally want to hook directly to runCode without needing all this!
  def assertOutputWith[K <: LanguageInterpreter[IO]](mkInterp: (KernelContext, Topic[IO, KernelStatusUpdate]) => K, code: Seq[String])(assertion: (K, Map[String, Any], Seq[Result], Seq[(String, String)]) => IO[Unit]): Unit = {
    Topic[IO, KernelStatusUpdate](UpdatedTasks(Nil)).flatMap { updates =>
      val kernelContext = getKernelContext(updates)
      val interp = mkInterp(kernelContext, updates)
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
            runResults <-
            code.zipWithIndex.foldLeft(IO.pure((cellContext, IO.pure(Vector.empty[Result])))) {
              case (accIO, (c, idx)) =>
                for {
                  acc <- accIO
                  (prevCtx, prevRes) = acc
                  ctx <- CellContext((idx + 1).toShort, Some(prevCtx))
                  prev <- prevRes
                  curr <- interp.runCode(ctx, c).flatMap(_.compile.toVector)
                } yield (ctx, IO.pure(prev ++ curr))
            }
            results <- runResults._2
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
