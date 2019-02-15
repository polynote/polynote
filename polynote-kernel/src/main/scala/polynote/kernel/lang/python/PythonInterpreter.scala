package polynote.kernel.lang
package python

import java.io.File
import java.util.concurrent.{Callable, ExecutorService, Executors, ThreadFactory}

import cats.effect.internals.IOContextShift
import cats.effect.{ContextShift, IO}
import fs2.Stream
import fs2.concurrent.{Enqueue, Queue}
import jep.python.{PyCallable, PyObject}
import jep.{Jep, JepConfig}
import polynote.kernel.PolyKernel.EnqueueSome
import polynote.kernel._
import polynote.kernel.context.{GlobalInfo, RuntimeContext}
import polynote.kernel.util.{Publish, ReadySignal}
import polynote.messages.{ShortString, TinyList, TinyString}

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext

class PythonInterpreter(val globalInfo: GlobalInfo) extends LanguageKernel[IO, GlobalInfo] {

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
  val listenerShift: ContextShift[IO] = IOContextShift(ExecutionContext.fromExecutor(externalListener))

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

  private val shutdownSignal = ReadySignal()(listenerShift)

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
    runtimeContext: RuntimeContext[GlobalInfo],
    code: String
  ): IO[(Stream[IO, Result], IO[RuntimeContext[GlobalInfo]])] = {
    if (code.trim().isEmpty) {
      IO.pure((
        Stream.empty,
        IO(RuntimeContext(cell, globalInfo, Option(runtimeContext), Map.empty, None, None))
      ))
    } else {

      implicit val iShift: ContextShift[IO] = shift

      def exec(stdout: PythonDisplayHook, globalDict: Map[String, Any]): IO[(Option[Output], Map[String, globalInfo.RuntimeValue], Option[globalInfo.RuntimeValue])] = {
        import runtimeContext.globalInfo.global

        global.demandNewCompilerRun()
        val run = new global.Run()
        global.newCompilationUnit("", cell)

        withJep {

          jep.set("__polynote_displayhook__", stdout)
          jep.eval("import sys\n")
          jep.eval("import ast\n")
          jep.eval("sys.displayhook = __polynote_displayhook__.output\n")
          jep.eval("sys.stdout = __polynote_displayhook__\n")
          jep.eval("__polynote_locals__ = {}\n")

          // TODO: maybe we need to add some more globals stuff here
          // globals needs to be a dict but if we send a Map to jep it becomes a pyjmap, so we need to populate a dict
          jep.eval("__polynote_globals__ = {}")
          globalDict.foreach {
            case (k, v) =>
              jep.set(s"__tmp_$k", v)
              jep.eval(s"__polynote_globals__.update({'$k': __tmp_$k})")
              jep.eval(s"del __tmp_$k")
          }

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
          jep.eval("exec(__polynote_code__, __polynote_globals__, __polynote_locals__)\n")
          jep.eval("globals().update(__polynote_locals__)") // still need to update globals so we can eval stuff
          val newDecls = jep.getValue("list(__polynote_locals__.keys())", classOf[java.util.List[String]]).asScala.toList

      // TODO: We talked about potentially creating an `Out` map, inspired by ipython, instead of these resCell# vars
      val (maybeOutput, maybeReturns) = if (lastIsExpr) {
        val hasLastValue = jep.getValue(s"$resultName != None", classOf[java.lang.Boolean])
        if (hasLastValue) {
          (
            Option(Output("text/plain; rel=decl; lang=python", s"$resultName = ${jep.getValue(s"repr(${newDecls.last})", classOf[String])}")),
            Option(globalInfo.RuntimeValue(resultName, jep.getValue(resultName), Some(this), cell))
          )
        } else (None, None)
      } else (None, None)

          val symbolDict = getPyResults(newDecls, cell).filterNot { case (k, v) => k == resultName || v == null }

          val symbols = symbolDict.map {
            case (name, value) => name -> globalInfo.RuntimeValue(name, value, Some(this), cell)
          }

          (maybeOutput, symbols, maybeReturns)
        }
      }

      Queue.unbounded[IO, Option[Result]].flatMap { maybeResultQ =>
        val eval = for {
          globalDict <- symbolsToPythonDict(runtimeContext.visibleSymbols)
          resultQ = new EnqueueSome(maybeResultQ)
          stdout = new PythonDisplayHook(resultQ)
          execResult <- exec(stdout, globalDict)
          (maybeOutput, symbols, maybeReturns) = execResult
          _ <- IO(stdout.flush())
          _ <- Stream.emits(maybeOutput.toSeq).to(resultQ.enqueue).compile.toVector
        } yield {


          // TODO: this IO isn't really useful. How can we return the Stream and IO before we are ready?
          val newCtx = IO(RuntimeContext(cell, globalInfo, Option(runtimeContext), symbols, None, maybeReturns))
          (maybeResultQ.dequeue.unNoneTerminate, newCtx)
        }
        eval.guarantee(maybeResultQ.enqueue1(None))
      }
    }
  }

  def getPyResults(decls: Seq[String], sourceCellId: String): Map[String, GlobalInfo#RuntimeValue] =
    decls.map {
      name => name -> {
        getPyResult(name) match {
          case (value, Some(typ)) => globalInfo.RuntimeValue(globalInfo.global.TermName(name), value, typ, Some(this), sourceCellId)
          case (value, _) => globalInfo.RuntimeValue(name, value, Some(this), sourceCellId)
        }
      }
    }.toMap

  def getPyResult(accessor: String): (Any, Option[globalInfo.global.Type]) = {
    val resultType = jep.getValue(s"type($accessor).__name__", classOf[String])
    import globalInfo.global.{typeOf, weakTypeOf}
    resultType match {
      case "int" => jep.getValue(accessor, classOf[java.lang.Number]).longValue() -> Some(typeOf[Long])
      case "float" => jep.getValue(accessor, classOf[java.lang.Number]).doubleValue() -> Some(typeOf[Double])
      case "str" => jep.getValue(accessor, classOf[String]) -> Some(typeOf[String])
      case "bool" => jep.getValue(accessor, classOf[java.lang.Boolean]).booleanValue() -> Some(typeOf[Boolean])
      case "tuple" => getPyResult(s"list($accessor)")
      case "dict" =>
        val keys = getPyResult(s"list($accessor.keys())")._1.asInstanceOf[List[Any]]
        val values = getPyResult(s"list($accessor.values())")._1.asInstanceOf[List[Any]]
        keys.zip(values).toMap -> Some(typeOf[Map[Any, Any]])
      case "list" =>
        val numElements = jep.getValue(s"len($accessor)", classOf[java.lang.Number]).longValue()
        val (elems, types) = (0L until numElements).map(n => getPyResult(s"$accessor[$n]")).toList.unzip

        val listType = globalInfo.global.ask {
          () =>
            val lubType = types.flatten.toList match {
              case Nil      => typeOf[Any]
              case typeList => try globalInfo.global.lub(typeList) catch {
                case err: Throwable => typeOf[Any]
              }
            }
            globalInfo.global.appliedType(typeOf[List[Any]].typeConstructor, lubType)
        }

        elems -> Some(listType)
      case "PyJObject" =>
        jep.getValue(accessor) -> Some(typeOf[AnyRef])
      case _ =>
        jep.getValue(accessor, classOf[PyObject]) -> Some(typeOf[PyObject])
    }
  }

  def symbolsToPythonDict(symbols: Seq[GlobalInfo#RuntimeValue]): IO[Map[String, Any]] = withJep {
    symbols.map {
      case globalInfo.RuntimeValue(name, value, _, _, _) =>
        value match {
          case polynote.runtime.Runtime =>
            // hijack the kernel and wrap it in a pyobject so we can set the display
            // we need to do this because it looks like jep doesn't handle the Runtime.display object properly
            // (maybe because it doesn't fully support Scala).
            // we need to create a new Python class that proxies the runtime object so that we can use
            // grab it as a PyObject from jep. We need a PyObject so we can use setAttr.
            // Then we can set the `display` attribute to the display object ourselves.

            // put Runtime ref into python-land
            jep.set("__kernel_ref", polynote.runtime.Runtime)
            // define our proxy object
            jep.eval(
              """
                |class KernelProxy(object):
                |    def __init__(self, ref):
                |        self.ref = ref
                |
                |    def __getattr__(self, name):
                |        return getattr(self.ref, name)
              """.stripMargin.trim)
            // create an instance of our proxy which delegates to runtime
            jep.eval("__kp = KernelProxy(__kernel_ref)")
            // pull the proxy out as a PyObject so we can mess with it
            val pykernel = jep.getValue("__kp", classOf[PyObject])
            // set the display attribute!
            pykernel.setAttr("display", polynote.runtime.Runtime.display)

            // clean up after ourselves
            jep.eval("del __kp")
            jep.eval("del __kernel_ref")

            // return the converted kernel reference so we can use it
            "kernel" -> pykernel
          case _ =>
            name.toString -> value
        }
    }.toMap
  }

  override def completionsAt(
    cell: String,
    runtimeContext: RuntimeContext[GlobalInfo],
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
    runtimeContext: RuntimeContext[GlobalInfo],
    code: String,
    pos: Int
  ): IO[Option[Signatures]] = IO.pure(None)

}

object PythonInterpreter {
  class Factory extends LanguageKernel.Factory[IO, GlobalInfo] {
    override val languageName: String = "Python"
    override def apply(dependencies: List[(String, File)], globalInfo: GlobalInfo): LanguageKernel[IO, GlobalInfo] =
      new PythonInterpreter(globalInfo)
  }

  def factory(): Factory = new Factory()

}

class PythonDisplayHook(out: Enqueue[IO, Result]) {
  private var current = ""
  def output(str: String): Unit =
    if (str != null && str.nonEmpty)
      out.enqueue1(Output("text/plain; rel=stdout", str)).unsafeRunSync() // TODO: probably should do better than this

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
