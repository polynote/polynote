package polynote.kernel.lang
package python

import java.io.File
import java.util.concurrent.{Callable, ExecutorService, Executors, ThreadFactory}

import cats.effect.internals.IOContextShift
import cats.effect.{ContextShift, IO}
import fs2.concurrent.Enqueue
import jep.python.{PyCallable, PyObject}
import jep.{Jep, JepConfig}
import polynote.kernel._
import polynote.kernel.util.{Publish, ReadySignal, RuntimeSymbolTable}
import polynote.messages.{ShortString, TinyList, TinyString}

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext

class PythonInterpreter(val symbolTable: RuntimeSymbolTable) extends LanguageKernel[IO] {

  val predefCode: Option[String] = None

  override def shutdown(): IO[Unit] = shutdownSignal.complete.flatMap(_ => withJep(jep.close()))

  private def newSingleThread(name: String): ExecutorService = Executors.newSingleThreadExecutor {
    new ThreadFactory {
      def newThread(r: Runnable): Thread = {
        val thread = new Thread(r)
        thread.setDaemon(true)
        thread.setName(name)
        thread
      }
    }
  }

  private val jepExecutor = newSingleThread("JEP execution thread")
  private val externalListener = newSingleThread("Python symbol listener")

  private val executionContext = ExecutionContext.fromExecutor(jepExecutor)

  private val shift: ContextShift[IO] = IOContextShift(executionContext)

  def withJep[T](t: => T): IO[T] = shift.evalOn(executionContext)(IO.delay(t))

  private val jep = jepExecutor.submit {
    new Callable[Jep] {
      def call(): Jep = {
        val jep = new Jep(new JepConfig().addSharedModules("numpy").setInteractive(false))
//        jep.eval(
//          """def __polynote_export_value__(d, k):
//            |  globals().update({ "__polynote_exported__": d[k] })
//          """.stripMargin)
        jep
      }
    }
  }.get()

  private val shutdownSignal = {
    // in a block to contain the implicit
    implicit val listenerShift: ContextShift[IO] = IOContextShift(ExecutionContext.fromExecutor(externalListener))
    val signal = ReadySignal()

    // push external symbols to Jep
    // TODO: The downside of doing this eagerly is that Python code won't forbid using symbols defined in later cells.
    //       Should that be fixed? Maybe we could build a new locals dict during runCode?
    // TODO: Can there be some special treatment for function values to make them Callable in python?
    // TODO: Instead of doing this, can we somehow just make the symbol table a Python scope?
    symbolTable.subscribe(Option(this)) {
      value => withJep {
        value.value match {
          case polynote.runtime.Runtime => // pass
          case _ =>
            jep.set(value.name.toString, value.value)
        }
      }
    }.interruptWhen(signal())
      .compile
      .drain
      .unsafeRunAsyncAndForget()

    withJep {
      val terms = symbolTable.currentTerms
      terms.foreach {
        value =>
          value.value match {
            case polynote.runtime.Runtime =>
              // hijack the kernel and wrap it in a pyobject so we can set the display
              jep.set("__kernel_ref", polynote.runtime.Runtime)
              jep.eval(
                """
                  |class KernelProxy(object):
                  |    def __init__(self, ref):
                  |        self.ref = ref
                  |
                  |    def __getattr__(self, name):
                  |        return getattr(self.ref, name)
                """.stripMargin.trim)
              jep.eval("kernel = KernelProxy(__kernel_ref)")
              val pykernel = jep.getValue("kernel", classOf[PyObject])
              pykernel.setAttr("display", polynote.runtime.Runtime.display)
              jep.eval("del kernel")
              jep.set("kernel", pykernel)
              jep.eval("del __kernel_ref")
            case _ =>
              jep.set(value.name.toString, value.value)
          }
      }
    }.unsafeRunSync()

    signal
  }

  // TODO: This is kind of a slow way to do this conversion, as it will result in a lot of JNI calls, but it is
  //       necessary to work around https://github.com/ninia/jep/issues/43 until that gets resolved. We don't want
  //       anything being converted to String except Strings. It's further complicated by the lack of being able to
  //       force PyObject return for invoke(), so there is some python-side stuff that we have to do.
  private def convertPythonValue(jep: Jep, value: PyObject): Any = {

    def convertPythonDict(dict: PyObject): Map[String, Any] = {
      val keys = jep.invoke("__builtins__.dict.keys", dict).asInstanceOf[java.util.List[String]].asScala.toSeq
      keys.map {
        key =>
          jep.invoke("__polynote_export_value__", dict, key)
          key -> convertPythonValue(jep, jep.getValue("__polynote_exported__", classOf[PyObject]))
      }.toMap
    }

    val typStr = jep.invoke("__builtins__.type", value).asInstanceOf[PyCallable].getAttr("__name__").asInstanceOf[String]
    typStr match {
      case "dict" =>
    }
  }

  override def runCode(
    cell: String,
    visibleSymbols: Seq[Decl],
    previousCells: Seq[String],
    code: String,
    out: Enqueue[IO, Result],
    statusUpdates: Publish[IO, KernelStatusUpdate]
  ): IO[Unit] = if (code.trim().isEmpty) IO.unit else {
    val stdout = new PythonDisplayHook(out)
    withJep {

      jep.set("__polynote_displayhook__", stdout)
      jep.eval("import sys\n")
      jep.eval("import ast\n")
      jep.eval("sys.displayhook = __polynote_displayhook__.output\n")
      jep.eval("sys.stdout = __polynote_displayhook__\n")
      jep.eval("__polynote_locals__ = {}\n")
      jep.set("__polynote_code__", code)
      jep.set("__polynote_cell__", cell)

      // all of this parsing is just so if the last statement is an expression, we can print the value like the repl does
      // TODO: should probably just use ipython to evaluate it instead
      jep.eval("__polynote_parsed__ = ast.parse(__polynote_code__, __polynote_cell__, 'exec').body\n")
      jep.eval("__polynote_last__ = __polynote_parsed__[-1]")
      val lastIsExpr = jep.getValue("isinstance(__polynote_last__, ast.Expr)", classOf[java.lang.Boolean])

      val resultName = s"res$cell"
      val maybeModifiedCode = if (lastIsExpr) {
        val lastLine = jep.getValue("__polynote_last__.lineno", classOf[java.lang.Integer]) - 1
        val (prevCode, lastCode) = code.linesWithSeparators.toSeq.splitAt(lastLine)

        (prevCode ++ s"$resultName = (${lastCode.mkString})\n").mkString
      } else code

      jep.set("__polynote_code__", maybeModifiedCode)
      jep.eval("exec(__polynote_code__, None, __polynote_locals__)\n")
      jep.eval("globals().update(__polynote_locals__)")
      val newDecls = jep.getValue("list(__polynote_locals__.keys())", classOf[java.util.List[String]]).asScala.toList

      // TODO: We talked about potentially creating an `Out` map, inspired by ipython, instead of these resCell# vars
      if (newDecls.last == resultName){
        val hasLastValue = jep.getValue(s"$resultName != None", classOf[java.lang.Boolean])
        if (hasLastValue) {
          stdout.flush()
          stdout.write(s"$resultName = ${jep.getValue(s"str(${newDecls.last})", classOf[String])}\n")
          stdout.flush()
        }
      }

      getPyResults(newDecls)

    }.flatMap {
      resultDict =>
        //val types = jep.getValue("""[type(dict.get(__polynote_locals__, x)).__name__ for x in dict.keys(__polynote_locals__) if not x.startswith("__")]""")
        //val resultDict = jep.getValue("__polynote_locals__", classOf[java.util.HashMap[String, Any]])
        symbolTable.publishAll(resultDict.toList.filterNot(_._2 == null).map {
          case (name, value) => symbolTable.RuntimeValue(name, value, Some(this), cell)
        })
    }.flatMap {
      _ => IO(stdout.flush())
    }
  }

  def getPyResults(decls: Seq[String]): Map[String, Any] = {
    decls.map { name =>
      val resultType = jep.getValue(s"type($name).__name__", classOf[String])
      val typedResult = resultType match {
        case "int" => jep.getValue(name, classOf[java.lang.Number]).longValue()
        case "float" => jep.getValue(name, classOf[java.lang.Number]).doubleValue()
        case "str" => jep.getValue(name, classOf[String])
        case "bool" => jep.getValue(name, classOf[java.lang.Boolean])
        case "tuple" => getPyResults(Seq(s"list($name)"))
        case "dict" =>
          val numElements = jep.getValue(s"len($name)", classOf[java.lang.Number]).longValue()
          val keyElems = (0L until numElements).map(n => s"list($name.items())[$n][0]")
          val valElems = (0L until numElements).map(n => s"list($name.items())[$n][1]")

          val keys = getPyResults(keyElems).values
          val values = getPyResults(valElems).values
          keys.zip(values).toMap
        case "list" =>
          val numElements = jep.getValue(s"len($name)", classOf[java.lang.Number]).longValue()
          val elems = (0L until numElements).map(n => s"$name[$n]")
          getPyResults(elems).values.toList
        case "PyJObject" => jep.getValue(name)
        case _ => jep.getValue(name, classOf[PyObject])
      }
      name -> typedResult
    }.toMap
  }

  override def completionsAt(
    cell: String,
    visibleSymbols: Seq[Decl],
    previousCells: Seq[String],
    code: String,
    pos: Int
  ): IO[List[Completion]] = withJep {
      val (line, col) = {
        val iter = code.linesWithSeparators
        var p = 0
        var l = 0
        var c = 0
        while (p < pos && iter.hasNext) {
          val line = iter.next()
          l += 1
          c = line.length()
          p = p + c
          if (p > pos) {
            c = line.length - (p - pos)
          } else if (p == pos && iter.hasNext) {
            l += 1
            c = 0
          }
        }
        (l, c)
      }
      jep.set("__polynote_code__", code)
      jep.eval("import jedi")
      jep.eval(s"__polynote_cmp__ = jedi.Interpreter(__polynote_code__, [globals(), locals()], line=$line, column=$col).completions()")
      // If this comes back as a List, Jep will mash all the elements to strings. So gotta use it as a PyObject. Hope that gets fixed!
      // TODO: will need some reusable PyObject wrappings anyway
      jep.getValue("__polynote_cmp__", classOf[Array[PyObject]]).map {
        obj =>
          val name = obj.getAttr("name", classOf[String])
          val typ = obj.getAttr("type", classOf[String])
          val params = if (typ != "function") Nil else List(
            TinyList(
              obj.getAttr("params", classOf[Array[PyObject]]).map {
                paramObj =>
                  TinyString(paramObj.getAttr("name", classOf[String])) -> ShortString("")
              }.toList
            )
          )

          val completionType = typ match {
            case "module" => CompletionType.Module
            case "function" => CompletionType.Method
            case "instance" => CompletionType.Term
            case "class" => CompletionType.ClassType
            case "keyword" => CompletionType.Keyword
            case "statement" => CompletionType.Term
            case _ => CompletionType.Unknown
          }
          Completion(name, Nil, params, ShortString(""), completionType)
      }.toList
  }

  // TODO: can parameter hints be implemented for python?
  override def parametersAt(
    cell: String,
    visibleSymbols: Seq[Decl],
    previousCells: Seq[String],
    code: String,
    pos: Int
  ): IO[Option[Signatures]] = IO.pure(None)

}

object PythonInterpreter {
  class Factory extends LanguageKernel.Factory[IO] {
    override def apply(dependencies: List[(String, File)], symbolTable: RuntimeSymbolTable): LanguageKernel[IO] =
      new PythonInterpreter(symbolTable)
  }

  def factory(): LanguageKernel.Factory[IO] = new Factory()

}

class PythonDisplayHook(out: Enqueue[IO, Result]) {
  private var current = ""
  def output(str: String): Unit =
    if (str != null && str.nonEmpty) polynote.runtime.Runtime.display.content("text/plain; lang=python", str)

  def write(str: String): Unit = synchronized {
    current = current + str
    if (str contains '\n') {
      val lines = current.split('\n')
      output(lines.dropRight(1).mkString("\n"))
      current = lines.last
    }
  }

  def flush(): Unit = synchronized {
    if (current != "")
      output(current)
    current = ""
  }
}
