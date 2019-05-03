package polynote.kernel.lang
package python

import java.io.File
import java.nio.{ByteBuffer, DoubleBuffer, FloatBuffer, IntBuffer, LongBuffer}
import java.util.concurrent._
import java.util.concurrent.atomic.AtomicReference

import cats.effect.{ContextShift, IO}
import fs2.Stream
import fs2.concurrent.{Enqueue, Queue}
import jep.python.{PyCallable, PyObject}
import jep.{DirectNDArray, Jep, JepConfig, NamingConventionClassEnquirer}
import org.log4s.Logger
import polynote.kernel.PolyKernel.EnqueueSome
import polynote.kernel._
import polynote.kernel.util._
import polynote.messages.{CellID, ShortString, TinyList, TinyString}
import polynote.runtime.python.{PythonFunction, PythonObject, TypedPythonObject}

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext

class PythonInterpreter(val kernelContext: KernelContext) extends LanguageInterpreter[IO] {
  import kernelContext.global

  protected val logger: Logger = org.log4s.getLogger

  val predefCode: Option[String] = None

  override def shutdown(): IO[Unit] = shutdownSignal.complete.flatMap(_ => withJep(jep.close()))

  override def init(): IO[Unit] = withJep {
    jep.set("__kernel_ref", polynote.runtime.Runtime)

    // Since Scala uses getter methods instead of class fields (i.e. you'd need `kernel.display().html()` instead of
    // `kernel.display.html`), we do a little bit of acrobatics here – we create an object in Python that wraps the
    // Runtime, and dynamically set an attribute `display` on it with Jep so that it's accessible as an attribute
    // rather than a method.
    jep.eval(
      """
        |class KernelProxy(object):
        |    def __init__(self, ref):
        |        self.ref = ref
        |
        |    def __getattr__(self, name):
        |        return getattr(self.ref, name)
        |""".stripMargin)
    jep.eval("kernel = KernelProxy(__kernel_ref)")
    val pykernel = jep.getValue("kernel", classOf[PyObject])
    pykernel.setAttr("display", polynote.runtime.Runtime.display)
    jep.eval("del kernel")
    jep.set("kernel", pykernel)
    jep.eval("del __kernel_ref")

    // this function is used to build a globals dict from the CellContext visibleSymbols, and then merge in the
    // python interpreter's globals dict. This lets us bring in external values without clobbering any native-python
    // version that already exists in the python interpreter.
    // TODO: what about python variables that have since been shadowed by external variables? Maybe should be
    //       smarter about which things to pull from globals dict?
    jep.eval(
      """def __pn_expand_globals__(syms):
        |    g = globals()
        |    pg = dict(syms)
        |    for k in g:
        |        pg[k] = g[k]
        |    return pg
      """.stripMargin.trim)


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

    override def asScalaList(obj: PythonObject): List[Any] = run {
      jep.set("___anon___", obj.unwrap)
      jep.eval("___anon___ = list(___anon___)")
      val result = getPyResult("___anon___").map(_._1.asInstanceOf[List[Any]]).getOrElse(Nil)
      jep.eval("del ___anon___")
      result
    }

    override def asScalaMap(obj: PythonObject): Map[Any, Any] = run {
      jep.set("___anon___", obj.unwrap)
      jep.eval("___anon___ = dict(___anon___)")
      val result = getPyResult("___anon___").map(_._1.asInstanceOf[Map[Any, Any]]).getOrElse(Map.empty)
      jep.eval("del ___anon___")
      result
    }
  }

  private val externalListener = newSingleThread("Python symbol listener")

  private val executionContext = ExecutionContext.fromExecutor(jepExecutor)

  private val jepShift: ContextShift[IO] = IO.contextShift(executionContext)
  private implicit val globalShift: ContextShift[IO] = IO.contextShift(kernelContext.executionContext)

  def withJep[T](t: => T): IO[T] = jepShift.evalOn(executionContext)(IO.delay(t))

  protected def sharedModules: List[String] = List("numpy", "google")

  protected val jep: Jep = jepExecutor.submit {
    new Callable[Jep] {
      def call(): Jep = {
        val jep = new Jep(
          new JepConfig()
            .addSharedModules(sharedModules: _*)
            .setInteractive(false)
            .setClassLoader(kernelContext.classLoader)
            .setClassEnquirer(new NamingConventionClassEnquirer(true)))
        jep
      }
    }
  }.get()

  private val shutdownSignal = ReadySignal()

  private def expandGlobals(): Unit = {
    jep.eval("__polynote_globals__ = __pn_expand_globals__(__polynote_globals__)")
  }

  override def runCode(
    cellContext: CellContext,
    code: String
  ): IO[Stream[IO, Result]] = if (code.trim().isEmpty) IO.pure(Stream.empty) else {
    val run = new global.Run()

    val cell = cellContext.id
    val cellName = s"Cell$cell"
    global.newCompilationUnit("", cellName)

    Queue.unbounded[IO, Option[Result]].flatMap { maybeResultQ =>
      // TODO: Jep doesn't give us a good way to construct a dict... should we wrap cell context in a class that emulates dict instead?
      val globals = cellContext.visibleValues.view.map {
        rv => Array(rv.name, rv.value)
      }.toArray

      val resultQ = new EnqueueSome(maybeResultQ)

      val stdout = new PythonDisplayHook(resultQ)


      val run = withJep {

        jep.set("__polynote_displayhook__", stdout)
        jep.eval("import sys\n")
        jep.eval("import ast\n")
        jep.eval("sys.displayhook = __polynote_displayhook__.output\n")
        jep.eval("sys.stdout = __polynote_displayhook__\n")
        jep.eval("__polynote_locals__ = {}\n")
        jep.set("__polynote_code__", code)
        jep.set("__polynote_cell__", cellName)
        jep.set("__polynote_globals__", globals)
        expandGlobals()
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

            jep.eval("exec(__polynote_code__, __polynote_globals__, __polynote_locals__)\n")
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
        } yield ()
      }.handleErrorWith {
        err => resultQ.enqueue1(RuntimeError(err))
      }.guarantee(maybeResultQ.enqueue1(None))

      run.start.map {
        fiber =>
          maybeResultQ.dequeue.unNoneTerminate ++ Stream.eval(fiber.join).drain
      }
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

      case "ndarray" =>
        // TODO: I'm a little torn here. Jep's native codepath won't allow us to get a PyObject for an ndarray; we have
        //       to convert it to a Java array which involves copying. Should we avoid doing that and just not export
        //       ndarrays? They can be pretty large. We ought to fix this up in jep itself so that we can either
        //       get a DirectNDArray out of python (currently those have to be created in Java) or just be able to get
        //       a PyObject.
        //       For now we'll go a head and copy out the Java array if possible.
        {
          jep.getValue(s"$accessor.dtype.name", classOf[String]) match {
            case "int64"   => Some(classOf[Array[Long]] -> typeOf[Array[Long]])
            case "int32"   => Some(classOf[Array[Int]] -> typeOf[Array[Int]])
            case "float64" => Some(classOf[Array[Double]] -> typeOf[Array[Double]])
            case "float32" => Some(classOf[Array[Float]] -> typeOf[Array[Float]])
            case "bool"    => Some(classOf[Array[Boolean]] -> typeOf[Array[Boolean]])
            case "int16"   => Some(classOf[Array[Short]] -> typeOf[Array[Short]])
            case _ => None
          }
        }.flatMap {
          case (cls, typ) => Option(jep.getValue(accessor, cls)).map {
            arrayObj => arrayObj -> Some(typ)
          }
        }

      case name =>
        Option(jep.getValue(accessor, classOf[PyObject])).map {
          pyObj =>
            // make a refined PythonObject type with the name of the type as a constant string type
            val objType = global.appliedType(typeOf[TypedPythonObject[String]].typeConstructor, global.internal.constantType(global.Constant(name)))
            new TypedPythonObject[String](pyObj, runner) -> Some(objType)
        }
    }
  }

  // want to avoid re-populating globals at every completion request. We'll only do it when the cell we're completing changes.
  private var lastCompletionCell = -1

  override def completionsAt(
    cellContext: CellContext,
    code: String,
    pos: Int
  ): IO[List[Completion]] = withJep {
      if (cellContext.id != lastCompletionCell) {
        lastCompletionCell = cellContext.id
        val globals = cellContext.visibleValues.view.map {
          rv => Array(rv.name, rv.value)
        }.toArray
        jep.set("__polynote_globals__", globals)
        expandGlobals()
      }

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
      jep.eval(s"__polynote_cmp__ = jedi.Interpreter(__polynote_code__, [__polynote_globals__, {}], line=$line, column=$col).completions()")
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

class PythonDisplayHook(out: Enqueue[IO, Result])(implicit contextShift: ContextShift[IO]) {
  private var current = ""
  def output(str: String): Unit =
    if (str != null && str.nonEmpty) {
      contextShift.shift.flatMap(_ => out.enqueue1(Output("text/plain; rel=stdout", str))).unsafeRunSync() // TODO: probably should do better than this
    }

  def write(str: String): Unit = synchronized {
    current = current + str
    if (str contains '\n') {
      val lines = current.split('\n')
      output((lines.dropRight(1) :+ "").mkString("\n"))
      current = lines.lastOption.fold("\n")(_ + "\n")
    }
  }

  def flush(): Unit = synchronized {
    if (current != "")
      output(current)
    current = ""
  }
}
