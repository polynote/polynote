package polynote.kernel.lang.python

import java.io.{BufferedReader, InputStreamReader, PrintWriter}
import java.util.concurrent.Executors

import cats.effect.internals.IOContextShift
import cats.effect.{ContextShift, ExitCode, IO}
import cats.syntax.apply._
import fs2.concurrent.{Queue, Topic}
import polynote.kernel.util.RuntimeSymbolTable
import polynote.kernel.{KernelStatusUpdate, Result, UpdatedTasks}
import org.scalatest._

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.tools.nsc.Settings
import scala.tools.nsc.interactive.Global
import scala.tools.nsc.reporters.ConsoleReporter


class PythonInterpreterSpec extends FlatSpec with Matchers {
  val settings = new Settings()
  settings.classpath.append(System.getProperty("java.class.path"))
  settings.YpresentationAnyThread.value = true
  implicit val contextShift: ContextShift[IO] = IOContextShift(ExecutionContext.fromExecutorService(Executors.newCachedThreadPool()))

  "The python kernel" should "be able to display html using the kernel runtime reference" in {
    var displayed = ("", "")
    polynote.runtime.Runtime.setDisplayer((mimeType, input) => displayed = (mimeType, input))
    assertOutput("kernel.display.html('hi')") { vars =>
      val kernel = vars("kernel")
      kernel shouldEqual polynote.runtime.Runtime
      val (mime, html) = displayed
      mime shouldEqual "text/html"
      html shouldEqual "hi"
    }
  }

  // TODO: we should consider refactoring the interpreter to improve testability so we don't need all this setup here.
  def assertOutput(code: String)(assertion: Map[String, Any] => Unit): Unit = {
    Topic[IO, KernelStatusUpdate](UpdatedTasks(Nil)).flatMap { updates =>
      val symbolTable = new RuntimeSymbolTable(new Global(settings, new ConsoleReporter(settings, new BufferedReader(new InputStreamReader(System.in)), new PrintWriter(System.out))), getClass.getClassLoader, updates)
      val interp = new PythonInterpreter(symbolTable)

      Queue.unbounded[IO, Result].flatMap {
        out =>
          Topic[IO, KernelStatusUpdate](UpdatedTasks(Nil)).flatMap {
            statusUpdates =>
              symbolTable.subscribe()(_ => IO.unit).map(_._1).interruptWhen {
                (interp.runCode("test", Nil, Nil, code, out, statusUpdates) *> symbolTable.drain()).attempt
              }.compile.toList.map {
                vars =>
                  val namedVars = vars.map(v => v.name.toString -> v.value).toMap

                  assertion(namedVars)
              }
          }
      }
    }.unsafeRunSync()
  }
}
