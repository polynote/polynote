package polynote.kernel.lang.python

import java.io.{BufferedReader, InputStreamReader, PrintWriter}

import cats.syntax.apply._
import cats.effect.{ExitCode, IO, IOApp}
import fs2.concurrent.{Queue, Topic}
import jep.python.PyObject
import polynote.kernel.util.RuntimeSymbolTable
import polynote.kernel.{KernelStatusUpdate, Output, Result, UpdatedTasks}

import scala.tools.nsc.Settings
import scala.tools.nsc.interactive.Global
import scala.tools.nsc.reporters.ConsoleReporter
import scala.collection.JavaConverters._

object PythonInterpreterTest extends IOApp {
  def run(args: List[String]): IO[ExitCode] = Topic[IO, KernelStatusUpdate](UpdatedTasks(Nil)).flatMap { updates =>

    val settings = new Settings()
    settings.classpath.append(System.getProperty("java.class.path"))
    settings.YpresentationAnyThread.value = true
    val symbolTable = new RuntimeSymbolTable(new Global(settings, new ConsoleReporter(settings, new BufferedReader(new InputStreamReader(System.in)), new PrintWriter(System.out))), getClass.getClassLoader, updates)
    val interp = new PythonInterpreter(symbolTable)

    val code =
      s"""foo = 20
         |bar = "wassup"
         |baz = ["hi", "hey", "howdy"]
         |""".stripMargin
    Queue.unbounded[IO, Result].flatMap {
      out => Topic[IO, KernelStatusUpdate](UpdatedTasks(Nil)).flatMap {
        statusUpdates =>
          symbolTable.subscribe()(_ => IO.unit).map(_._1).interruptWhen {
            (interp.runCode("test", Nil, Nil, code, out, statusUpdates) *> symbolTable.drain()).attempt
          }.compile.toList.map {
            vars =>
              val namedVars = vars.map(v => v.name.toString -> v.value).toMap
              val success = for {
                foo <- namedVars.get("foo") if foo == 20
                bar <- namedVars.get("bar") if bar == "wassup"
                baz <- namedVars.get("baz") if baz.asInstanceOf[java.util.List[String]].asScala.toList == List("hi", "hey", "howdy")
              } yield ExitCode.Success

              success.getOrElse(ExitCode.Error)
          }
      }
    }
  }
}
