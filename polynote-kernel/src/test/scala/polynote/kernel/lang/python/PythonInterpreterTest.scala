package polynote.kernel.lang.python

import java.io.{BufferedReader, InputStreamReader, PrintWriter}

import cats.effect.{ExitCode, IO, IOApp}
import fs2.concurrent.{Queue, Topic}
import jep.python.PyObject
import polynote.kernel.util.RuntimeSymbolTable
import polynote.kernel.{KernelStatusUpdate, Output, Result, UpdatedTasks}

import scala.tools.nsc.Settings
import scala.tools.nsc.interactive.Global
import scala.tools.nsc.reporters.ConsoleReporter

object PythonInterpreterTest extends IOApp {
  def run(args: List[String]): IO[ExitCode] = Topic[IO, KernelStatusUpdate](UpdatedTasks(Nil)).flatMap { updates =>

    val settings = new Settings()
    val interp = new PythonInterpreter(new RuntimeSymbolTable(new Global(settings, new ConsoleReporter(settings, new BufferedReader(new InputStreamReader(System.in)), new PrintWriter(System.out))), getClass.getClassLoader, updates))

    val code =
      s"""foo = 20
         |bar = "wassup"
         |baz = ["hi", "hey", "howdy"]
         |""".stripMargin
    Queue.unbounded[IO, Result].flatMap {
      out => Topic[IO, KernelStatusUpdate](UpdatedTasks(Nil)).flatMap {
        statusUpdates =>
          interp.runCode("test", Nil, Nil, code, out, statusUpdates).flatMap {
            vars =>
              IO(println(vars))
          }.map {
            _ => ExitCode.Success
          }
      }
    }
  }
}
