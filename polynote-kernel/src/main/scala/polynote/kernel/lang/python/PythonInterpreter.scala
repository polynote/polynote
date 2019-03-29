package polynote.kernel.lang
package python

import java.io.File
import java.util
import java.util.concurrent._
import java.util.concurrent.atomic.AtomicReference

import cats.effect.internals.IOContextShift
import cats.effect.{ContextShift, IO}
import fs2.Stream
import fs2.concurrent.{Enqueue, Queue}
import jep.python.{PyCallable, PyObject}
import jep.{Jep, JepConfig, NamingConventionClassEnquirer}
import org.log4s.Logger
import polynote.kernel.PolyKernel.EnqueueSome
import polynote.kernel._
import polynote.kernel.util._
import polynote.messages.{CellID, ShortString, TinyList, TinyString}
import polynote.runtime.python.{PythonFunction, PythonObject}

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext

class PythonInterpreter(val kernelContext: KernelContext) extends LanguageInterpreter[IO] {
  import kernelContext.global

  protected val logger: Logger = org.log4s.getLogger

  val predefCode: Option[String] = None

  override def shutdown(): IO[Unit] = shutdownSignal.complete.flatMap(_ => withJep(jep.close()))

  override def init(): IO[Unit] = withJep {
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
  }

  private val jepThread: AtomicReference[Thread] = new AtomicReference[Thread](null)

  private def newSingleThread(name: String, ref: Option[AtomicReference[Thread]] = None): ExecutorService = Executors.newSingleThreadExecutor {
    new ThreadFactory {
      def newThread(r: Runnable): Thread = {
        val thread = new Thread(r)
        thread.setDaemon(true)
        thread.setName(name)
        ref.foreach(_.set(thread))
        thread
      }
    }
  }

  private val jepExecutor = newSingleThread("JEP execution thread", Some(jepThread))

  // sharable runner for passing to PythonObjects – checks if we're on the Jep thread, runs immediately if so, otherwise
  // submits task to the jep executor. Otherwise, if we *were* on the jep thread, we would immediately deadlock.
  private val runner = new PythonObject.Runner {
    def run[T](task: => T): T = if (Thread.currentThread() == jepThread.get) {
      task
    } else {
      withJep(task).unsafeRunSync()
    }
  }

  private val externalListener = newSingleThread("Python symbol listener")

  private val executionContext = ExecutionContext.fromExecutor(jepExecutor)

  private val shift: ContextShift[IO] = IO.contextShift(executionContext)
  private val listenerShift: ContextShift[IO] = IO.contextShift(ExecutionContext.fromExecutor(externalListener))

  def withJep[T](t: => T): IO[T] = shift.evalOn(executionContext)(IO.delay(t))

  protected val jep: Jep = jepExecutor.submit {
    new Callable[Jep] {
      def call(): Jep = {
        val jep = new Jep(
          new JepConfig()
            .addSharedModules("numpy")
            .setInteractive(false)
            .setClassLoader(kernelContext.classLoader)
            .setClassEnquirer(new NamingConventionClassEnquirer(true)))
        jep
      }
    }
  }.get()

  private val shutdownSignal = ReadySignal()(listenerShift)

  override def runCode(
    cellContext: CellContext,
    code: String
  ): IO[Stream[IO, Result]] = if (code.trim().isEmpty) IO.pure(Stream.empty) else {
    val run = new global.Run()

    val cell = cellContext.id
    val cellName = s"Cell$cell"
    global.newCompilationUnit("", cellName)

    val shiftEffect = IO.ioConcurrentEffect(shift) // TODO: we can also create an implicit shift instead of doing this, which is better?
    Queue.unbounded[IO, Option[Result]](shiftEffect).flatMap { maybeResultQ =>
      // TODO: Jep doesn't give us a good way to construct a dict... should we wrap cell context in a class that emulates dict instead?
      val globals = cellContext.visibleValues.view.map {
        rv => Array(rv.name, rv.value)
      }.toArray

      val resultQ = new EnqueueSome(maybeResultQ)

      val stdout = new PythonDisplayHook(resultQ)


      withJep {

        jep.set("__polynote_displayhook__", stdout)
        jep.eval("import sys\n")
        jep.eval("import ast\n")
        jep.eval("sys.displayhook = __polynote_displayhook__.output\n")
        jep.eval("sys.stdout = __polynote_displayhook__\n")
        jep.eval("__polynote_locals__ = {}\n")
        jep.set("__polynote_code__", code)
        jep.set("__polynote_cell__", cellName)
        jep.set("__polynote_globals__", globals)

        // all of this parsing is just so if the last statement is an expression, we can print the value like the repl does
        // TODO: should probably just use ipython to evaluate it instead
        jep.eval("__polynote_parsed__ = ast.parse(__polynote_code__, __polynote_cell__, 'exec').body\n")
        val numStats = jep.getValue("len(__polynote_parsed__)", classOf[java.lang.Long])

        if (numStats > 0) {
          jep.eval("__polynote_last__ = __polynote_parsed__[-1]")
          val lastIsExpr = jep.getValue("isinstance(__polynote_last__, ast.Expr)", classOf[java.lang.Boolean])

          val resultName = "Out"
          val maybeModifiedCode = if (lastIsExpr) {
            val lastLine = jep.getValue("__polynote_last__.lineno", classOf[java.lang.Integer]) - 1
            val (prevCode, lastCode) = code.linesWithSeparators.toSeq.splitAt(lastLine)

            (prevCode ++ s"$resultName = (${lastCode.mkString})\n").mkString
          } else code

          jep.set("__polynote_code__", maybeModifiedCode)
          kernelContext.runInterruptible {
            jep.eval("exec(__polynote_code__, {**dict(__polynote_globals__), **globals()}, __polynote_locals__)\n")
          }
          jep.eval("globals().update(__polynote_locals__)")
          val newDecls = jep.getValue("list(__polynote_locals__.keys())", classOf[java.util.List[String]]).asScala.toList


          getPyResults(newDecls, cell).filterNot(_._2.value == null).values
        } else Nil
      }.flatMap { resultSymbols =>

        for {
          // send all symbols to resultQ
          _ <- Stream.emits(resultSymbols.toSeq).to(resultQ.enqueue).compile.drain
          // make sure to flush stdout
          _ <- IO(stdout.flush())
        } yield {

          // resultQ will be in this approximate order (not that it should matter I hope):
          //    1. stdout while running the cell
          //    2. The symbols defined in the cell
          //    3. The final output (if any) of the cell
          //    4. Anything that might come out of stdout being flushed (probably nothing)
          maybeResultQ.dequeue.unNoneTerminate
        }
      }.guarantee(maybeResultQ.enqueue1(None))
    }
  }

  def getPyResults(decls: Seq[String], sourceCellId: CellID): Map[String, ResultValue] = {
    decls.flatMap {
      name =>
        getPyResult(name).map {
          case (value, Some(typ)) =>
            ResultValue(kernelContext, name, typ, value, sourceCellId)
          case (value, _) =>
            ResultValue(kernelContext, name, value, sourceCellId)
        }.map {
          value => name -> value
        }
    }.toMap
  }

  // TODO: This is kind of a slow way to do this conversion, as it will result in a lot of JNI calls, but it is
  //       necessary to work around https://github.com/ninia/jep/issues/43 until that gets resolved. We don't want
  //       anything being converted to String except Strings.
  def getPyResult(accessor: String): Option[(Any, Option[global.Type])] = {
    val resultType = jep.getValue(s"type($accessor).__name__", classOf[String])
    import global.{typeOf, weakTypeOf}
    resultType match {
      case "int" => Option(jep.getValue(accessor, classOf[java.lang.Number]).longValue() -> Some(typeOf[Long]))
      case "float" => Option(jep.getValue(accessor, classOf[java.lang.Number]).doubleValue() -> Some(typeOf[Double]))
      case "str" => Option(jep.getValue(accessor, classOf[String]) -> Some(typeOf[String]))
      case "bool" => Option(jep.getValue(accessor, classOf[java.lang.Boolean]).booleanValue() -> Some(typeOf[Boolean]))
      case "tuple" => getPyResult(s"list($accessor)")
      case "dict" => for {
        keys <- getPyResult(s"list($accessor.keys())")
        values <- getPyResult(s"list($accessor.values())")
      } yield {
        keys._1.asInstanceOf[List[Any]].zip(values._1.asInstanceOf[List[Any]]).toMap -> Some(typeOf[Map[Any, Any]])
      }
      case "list" =>
        // TODO: this in particular is pretty inefficient... it does a JNI call for every element of the list.
        val numElements = jep.getValue(s"len($accessor)", classOf[java.lang.Number]).longValue()
        val (elems, types) = (0L until numElements).flatMap(n => getPyResult(s"$accessor[$n]")).toList.unzip

        val listType = global.ask {
          () =>
            val lubType = types.flatten.toList match {
              case Nil      => typeOf[Any]
              case typeList => try global.lub(typeList) catch {
                case err: Throwable => typeOf[Any]
              }
            }
            global.appliedType(typeOf[List[Any]].typeConstructor, lubType)
        }

        Option(elems -> Some(listType))
      case "PyJObject" =>
        Option(jep.getValue(accessor) -> Some(typeOf[AnyRef]))
      case "module" =>
        None
      case "function" | "builtin_function_or_method" | "type" =>
        try Option(new PythonFunction(jep.getValue(accessor, classOf[PyCallable]), runner) -> Some(typeOf[PythonFunction])) catch {
          case err: Throwable =>
            logger.info(err)("Error getting python object")
            None
        }

      case _ =>
        Option(jep.getValue(accessor, classOf[PyObject])).map {
          pyObj => new PythonObject(pyObj, runner) -> Some(typeOf[PythonObject])
        }
    }
  }

  override def completionsAt(
    cellContext: CellContext,
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
    cellContext: CellContext,
    code: String,
    pos: Int
  ): IO[Option[Signatures]] = IO.pure(None)

}

object PythonInterpreter {
  class Factory extends LanguageInterpreter.Factory[IO] {
    override val languageName: String = "Python"
    override def apply(dependencies: List[(String, File)], kernelContext: KernelContext): PythonInterpreter =
      new PythonInterpreter(kernelContext)
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
