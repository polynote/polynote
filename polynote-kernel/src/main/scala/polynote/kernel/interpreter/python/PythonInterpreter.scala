package polynote.kernel.interpreter
package python
import java.nio.file.{Files, Path, Paths}
import java.util
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.concurrent.{Executors, LinkedBlockingQueue, ThreadFactory}

import cats.syntax.traverse._
import cats.instances.list._
import jep.python.{PyCallable, PyObject}
import jep.{Jep, JepConfig, JepException, NamingConventionClassEnquirer, SharedInterpreter, SubInterpreter}
import polynote.config
import polynote.config.{PolynoteConfig, pip}
import polynote.kernel.environment.{Config, CurrentNotebook, CurrentRuntime, CurrentTask}
import polynote.kernel.{CompileErrors, Completion, CompletionType, InterpreterEnv, KernelReport, ParameterHint, ParameterHints, Pos, ResultValue, ScalaCompiler, Signatures, TaskManager}
import polynote.messages.{CellID, Notebook, NotebookConfig, ShortString, TinyList, TinyString}
import polynote.runtime.python.{PythonFunction, PythonObject, TypedPythonObject}
import zio.internal.{ExecutionMetrics, Executor}
import zio.blocking.{Blocking, effectBlocking}
import zio.{Runtime, Task, RIO, UIO, ZIO}
import zio.interop.catz._

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext
import scala.reflect.{ClassTag, classTag}
import scala.util.Try

class PythonInterpreter private[python] (
  compiler: ScalaCompiler,
  jepInstance: Jep,
  jepExecutor: Executor,
  jepThread: AtomicReference[Thread],
  jepBlockingService: Blocking,
  runtime: Runtime[Any],
  pyApi: PythonInterpreter.PythonAPI,
  venvPath: Option[Path],
  py4jError: String => Option[Throwable]
) extends Interpreter {
  import pyApi._

  private val runner: PythonObject.Runner = new PythonObject.Runner {
    def run[T](task: => T): T = if (Thread.currentThread() eq jepThread.get()) {
      task
    } else {
      runtime.unsafeRun(effectBlocking(task).lock(jepExecutor).provide(jepBlockingService))
    }

    def hasAttribute(obj: PythonObject, name: String): Boolean = run {
      hasAttr(obj.unwrap, name)
    }

    def asScalaList(obj: PythonObject): List[PythonObject] = run {
      val pyObj = obj.unwrap
      val getItem = pyObj.getAttr("__getitem__", classOf[PyCallable])
      val length = len(pyObj)

      List.tabulate(length)(i => getItem.callAs(classOf[PyObject], Int.box(i))).map(obj => new PythonObject(obj, this))
    }

    def asScalaMap(obj: PythonObject): Map[Any, Any] = asScalaMapOf[PyObject, PyObject](obj).map {
      case (k, v) => new PythonObject(k, runner) -> new PythonObject(v, runner)
    }

    override def asScalaMapOf[K : ClassTag, V : ClassTag](obj: PythonObject): Map[K, V] = run {
      val K = classTag[K].runtimeClass.asInstanceOf[Class[K]]
      val V = classTag[V].runtimeClass.asInstanceOf[Class[V]]
      val items = dictToItemsList(obj.unwrap)
      val getItem = items.getAttr("__getitem__", classOf[PyCallable])
      val length = len(items)
      val tuples = List.tabulate(length)(i => getItem.callAs(classOf[PyObject], Int.box(i)))
      val result = tuples.map {
        tup =>
          val tupleItem = tup.getAttr("__getitem__", classOf[PyCallable])
          tupleItem.callAs(K, Int.box(0)) -> tupleItem.callAs(V, Int.box(1))
      }.toMap
      result
    }
  }

  def run(code: String, state: State): RIO[InterpreterEnv, State] = for {
    parsed    <- parse(code, s"Cell${state.id}")
    compiled  <- compile(parsed)
    locals    <- eval[PyObject]("{}")
    globals   <- populateGlobals(state)
    _         <- injectGlobals(globals)
    resState  <- run(compiled, globals, locals, state)
  } yield resState

  def completionsAt(code: String, pos: Int, state: State): Task[List[Completion]] = populateGlobals(state).flatMap {
    globals => jep {
      jep =>
        val jedi = jep.getValue("jedi.Interpreter", classOf[PyCallable])
        val lines = code.substring(0, pos).split('\n')
        val lineNo = lines.length
        val col = lines.last.length
        val pyCompletions = jedi.callAs[PyObject](classOf[PyObject], Array[Object](code, Array(globals)), Map[String, Object]("line" -> Integer.valueOf(lineNo), "column" -> Integer.valueOf(col)).asJava)
          .getAttr("completions", classOf[PyCallable])
          .callAs(classOf[Array[PyObject]])

        pyCompletions.map {
          completion =>
            val name = completion.getAttr("name", classOf[String])
            val typ = completion.getAttr("type", classOf[String])
            val params = typ match {
              case "function" => List(TinyList(completion.getAttr("params", classOf[Array[PyObject]]).map {
                  paramObj =>
                    TinyString(paramObj.getAttr("name", classOf[String])) -> ShortString("")
                }.toList))
              case _ => Nil
            }
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
  }

  def parametersAt(code: String, pos: Int, state: State): Task[Option[Signatures]] = populateGlobals(state).flatMap {
    globals => jep {
      jep =>
        try {
          val lines = code.substring(0, pos).split('\n')
          val line = lines.length
          val col = lines.last.length
          val jedi = jep.getValue("jedi.Interpreter", classOf[PyCallable])
          val sig = jedi.callAs[PyObject](classOf[PyObject], Array[Object](code, Array(globals)), Map[String, Object]("line" -> Integer.valueOf(line), "column" -> Integer.valueOf(col)).asJava)
            .getAttr("call_signatures", classOf[PyCallable])
            .callAs(classOf[Array[PyObject]])
            .head

          val index = sig.getAttr("index", classOf[java.lang.Long])
          val getParams = jep.getValue("lambda sig: list(map(lambda p: [p.name, next(iter(map(lambda t: t.name, p.infer())), None)], sig.params))", classOf[PyCallable])
          val params = getParams.callAs(classOf[java.util.List[java.util.List[String]]], sig).asScala.map {
            tup =>
              val name = tup.get(0)
              val typeName = if (tup.size > 1) Option(tup.get(1)) else None
              ParameterHint(name, typeName.getOrElse(""), None) // TODO: can we parse per-param docstrings out of the main docstring?
          }

          val docString = Try(sig.getAttr("docstring", classOf[PyCallable]).callAs(classOf[String], java.lang.Boolean.TRUE))
            .map(_.toString.split("\n\n").head)
            .toOption.filterNot(_.isEmpty).map(ShortString.truncate)

          val name = s"${sig.getAttr("name", classOf[String])}(${params.mkString(", ")})"
          val hints = ParameterHints(
            name,
            docString,
            params.toList
          )
          Some(Signatures(List(hints), 0, index.byteValue()))
        } catch {
          case err: Throwable =>
            println(err)
            None
        }
    }
  }

  def init(state: State): RIO[InterpreterEnv, State] = for {
    _       <- exec(setup)
    _       <- exec(matplotlib)
    globals <- getValue("globals().copy()")
    scope   <- populateGlobals(state)
    _       <- jep { _ =>
      val update = globals.getAttr("update", classOf[PyCallable])
      update.call(scope)
    }
  } yield PythonState(state.id, state.prev, Nil, globals)

  def shutdown(): Task[Unit] = jep(_.close())

  protected[python] def jep[T](fn: Jep => T): Task[T] = effectBlocking(fn(jepInstance)).lock(jepExecutor).provide(jepBlockingService)
  protected[python] def exec(code: String): Task[Unit] = jep(_.exec(code))
  protected[python] def eval[T : ClassTag](code: String): Task[T] = jep(_.getValue(code, classTag[T].runtimeClass.asInstanceOf[Class[T]]))
  protected[python] def setValue(name: String, value: AnyRef): Task[Unit] = jep(_.set(name, value))
  protected[python] def getValue(name: String): Task[PyObject] = jep(_.getValue(name, classOf[PyObject]))


  protected def setup: String =
    """
      |import os, sys, ast, jedi, shutil
      |from pathlib import Path
      |from java.lang import RuntimeException, StackTraceElement
      |from java.util import ArrayList, HashMap
      |from polynote.kernel import Pos
      |from polynote.kernel import KernelReport
      |
      |class LastExprAssigner(ast.NodeTransformer):
      |
      |    # keep track of last line of initial tree passed in
      |    lastLine = None
      |
      |    def visit(self, node):
      |        if (not self.lastLine):
      |            if (len(node.body) == 0):
      |                return node
      |            self.lastLine = node.body[-1].lineno
      |        return super(ast.NodeTransformer, self).visit(node)
      |
      |    def visit_Expr(self, node):
      |        if node.lineno == self.lastLine:
      |            return ast.copy_location(ast.Assign(targets=[ast.Name(id='Out', ctx=ast.Store())], value=node.value), node)
      |        else:
      |            return node
      |
      |def __polynote_parse__(code, cell):
      |    try:
      |        return { 'result': ast.fix_missing_locations(LastExprAssigner().visit(ast.parse(code, cell, 'exec'))) }
      |    except SyntaxError as err:
      |        lines = code.splitlines(True)
      |        pos = sum([len(lines[x]) for x in range(0, err.lineno - 1)])
      |        pos = pos + err.offset
      |        return { 'error': KernelReport(Pos(cell, pos, pos, pos), err.msg, 2) }
      |
      |def __polynote_compile__(parsed):
      |    return list(map(lambda node: compile(ast.Module([node]), '<ast>', 'exec'), parsed.body))
      |
      |def __polynote_run__(compiled, _globals, _locals, kernel):
      |    try:
      |        sys.stdout = kernel.display
      |        for stat in compiled:
      |            exec(stat, _globals, _locals)
      |            _globals.update(_locals)
      |        types = { x: type(y).__name__ for x,y in _locals.items() }
      |        return { 'globals': _globals, 'locals': _locals, 'types': types }
      |    except Exception as err:
      |        import traceback
      |        typ, err_val, tb = sys.exc_info()
      |
      |        trace = ArrayList()
      |        for frame in traceback.extract_tb(tb):
      |            trace.add(StackTraceElement(frame.filename.split("/")[-1], frame.name, frame.filename, frame.lineno))
      |        result = { 'stack_trace': trace, 'message': getattr(err_val, 'message', str(err_val)), 'class': typ.__name__  }
      |
      |        if typ.__name__ == 'Py4JJavaError':
      |            result['py4j_error'] = err.java_exception._target_id
      |
      |        return result
      |
      |""".stripMargin

  protected def matplotlib: String =
    """
      |try:
      |    import matplotlib
      |
      |    from matplotlib._pylab_helpers import Gcf
      |    from matplotlib.backend_bases import (_Backend, FigureManagerBase)
      |    from matplotlib.backends.backend_agg import _BackendAgg
      |
      |    class FigureManagerTemplate(FigureManagerBase):
      |        def show(self):
      |            # Save figure as SVG for display
      |            import io
      |            buf = io.StringIO()
      |            self.canvas.figure.savefig(buf, format='svg')
      |            buf.seek(0)
      |            html = "<div style='width:600px'>" + buf.getvalue() + "</div>"
      |
      |            # Display image as html
      |            kernel.display.html(html)
      |
      |            # destroy the figure now that we've shown it
      |            Gcf.destroy_all()
      |
      |
      |    @_Backend.export
      |    class PolynoteBackend(_BackendAgg):
      |        __module__ = "polynote"
      |
      |        FigureManager = FigureManagerTemplate
      |
      |        @classmethod
      |        def show(cls):
      |            managers = Gcf.get_all_fig_managers()
      |            if not managers:
      |                return  # this means there's nothing to plot, apparently.
      |            for manager in managers:
      |                manager.show()
      |
      |
      |    import matplotlib
      |    matplotlib.use("module://" + PolynoteBackend.__module__)
      |
      |except ImportError as e:
      |    import sys
      |    print("No matplotlib support:", e, file=sys.stderr)
      |""".stripMargin

  protected def injectGlobals(globals: PyObject): RIO[CurrentRuntime, Unit] = CurrentRuntime.access.flatMap {
    runtime =>
      jep {
        jep =>
          val setItem = globals.getAttr("__setitem__", classOf[PyCallable])
          setItem.call("kernel", runtime)
          // TODO: Also update the global `kernel` so matplotlib integration (and other libraries?) can access the
          //       correct kernel. We may want to have a better way to do this though, like a first class getKernel()
          //       function for libraries (this only solves the problem for python)
          jep.set("kernel", runtime)
      }
  }

  protected def populateGlobals(state: State): Task[PyObject] = jep {
    jep =>
      val prevStates = state.takeUntil(_.isInstanceOf[PythonState]).reverse
      val (globalsDict, rest) = prevStates match {
        case PythonState(_, _, _, globalsDict) :: rest => (globalsDict.getAttr("copy", classOf[PyCallable]).callAs(classOf[PyObject]), rest)
        case others => (jep.getValue("{}", classOf[PyObject]), others)
      }

      val addGlobal = globalsDict.getAttr("__setitem__", classOf[PyCallable])

      rest.map(_.values).map {
        values => values.map(v => v.name -> v.value).toMap
      }.foldLeft(Map.empty[String, Any])(_ ++ _).foreach {
        case (name, value: PythonObject) => addGlobal.call(name, value.unwrap)
        case (name, value) => addGlobal.call(name, value.asInstanceOf[AnyRef])
      }

      globalsDict
  }

  protected def parse(code: String, cell: String): Task[PyObject] = jep {
    jep =>
      val result = jep.getValue("__polynote_parse__", classOf[PyCallable]).callAs(classOf[PyObject], code, cell)
      val get = result.getAttr("get", classOf[PyCallable])
      get.callAs(classOf[PyObject], "result") match {
        case null => get.callAs(classOf[KernelReport], "error") match {
          case null   => throw new IllegalStateException(s"No failure or success in python parse")
          case report => throw CompileErrors(List(report))
        }
        case result => result
      }
  }

  protected def compile(parsed: PyObject): Task[PyObject] = jep {
    jep =>
      val compile = jep.getValue("__polynote_compile__", classOf[PyCallable])
      compile.callAs(classOf[PyObject], parsed)
  }

  protected def run(compiled: PyObject, globals: PyObject, locals: PyObject, state: State): RIO[CurrentRuntime, State] =
    CurrentRuntime.access.flatMap {
      kernelRuntime => jep {
        jep =>

          val run = jep.getValue("__polynote_run__", classOf[PyCallable])
          val result = run.callAs(classOf[PyObject], compiled, globals, locals, kernelRuntime)
          val get = result.getAttr("get", classOf[PyCallable])

          get.callAs(classOf[util.ArrayList[Object]], "stack_trace") match {
            case null =>
              val globals = get.callAs(classOf[PyObject], "globals")
              val locals = get.callAs(classOf[PyObject], "locals")
              val types = get.callAs(classOf[java.util.Map[String, String]], "types")

              val localsItems = dictToItemsList(locals)
              val getLocal = localsItems.getAttr("__getitem__", classOf[PyCallable])
              val numLocals = len(localsItems)
              val resultValues = (0 until numLocals).map { i =>
                val item = getLocal.callAs(classOf[PyObject], Int.box(i))
                import compiler.global._
                if (item != null) {
                  val getField = item.getAttr("__getitem__", classOf[PyCallable])
                  val key = getField.callAs(classOf[String], Integer.valueOf(0))
                  val typeStr = types.get(key)

                  def valueAs[T](cls: Class[T]): T = getField.callAs(cls, Integer.valueOf(1))

                  if (typeStr != null && typeStr != "NoneType" && typeStr != "module") {
                    val (typ, value) = typeStr match {
                      case "int" => (typeOf[Long], valueAs(classOf[java.lang.Number]).longValue())
                      case "float" => (typeOf[Double], valueAs(classOf[java.lang.Number]).doubleValue())
                      case "str" => (typeOf[java.lang.String], valueAs(classOf[String]))
                      case "bool" => (typeOf[Boolean], valueAs(classOf[java.lang.Boolean]).booleanValue())
                      case "function" | "builtin_function_or_method" | "type" =>
                        (typeOf[PythonFunction], new PythonFunction(valueAs(classOf[PyCallable]), runner))
                      case "PyJObject" | "PyJCallable" | "PyJAutoCloseable" =>
                        val jValue = valueAs(classOf[Object])
                        val typ = runtime.unsafeRun(compiler.reflect(jValue)).symbol.info
                        (typ, jValue)
                      case other =>
                        // should we use the qualified type? It's confusing that both Spark and Pandas have a "DataFrame".
                        // But in every other case it's just noise.
                        val typ = appliedType(typeOf[TypedPythonObject[Nothing]].typeConstructor, compiler.global.internal.constantType(Constant(other)))
                        (typ, new TypedPythonObject[String](valueAs(classOf[PyObject]), runner))
                    }
                    Some(new ResultValue(key, compiler.unsafeFormatType(typ.asInstanceOf[Type]), Nil, state.id, value, typ.asInstanceOf[Type], None))
                  } else None
                } else None
              }.toList.flatten
              PythonState(state.id, state.prev, resultValues.toList, globals)

            case trace =>
              val cause = Option(get.callAs(classOf[String], "py4j_error")).flatMap(py4jError)
              val message = get.callAs(classOf[String], "message")
              val typ = get.callAs(classOf[String], "class")
              val els = trace.asScala.map(_.asInstanceOf[StackTraceElement]).toArray
              val err = cause.fold(new RuntimeException(s"$typ: $message"))(new RuntimeException(s"$typ: $message", _))
              err.setStackTrace(els)
              throw err
          }
      }
    }

  case class PythonState(id: CellID, prev: State, values: List[ResultValue], globalsDict: PyObject) extends State {
    override def withPrev(prev: State): State = copy(prev = prev)
    override def updateValues(fn: ResultValue => ResultValue): State = copy(values = values.map(fn))
    override def updateValuesM[R](fn: ResultValue => RIO[R, ResultValue]): RIO[R, State] =
      ZIO.sequence(values.map(fn)).map(values => copy(values = values))
  }
}

object PythonInterpreter {

  private[python] class PythonAPI(jep: Jep) {
    private val typeFn: PyCallable = jep.getValue("type", classOf[PyCallable])
    private val lenFn: PyCallable = jep.getValue("len", classOf[PyCallable])
    private val listFn: PyCallable = jep.getValue("list", classOf[PyCallable])
    private val dictToItemsListFn: PyCallable = jep.getValue("lambda x: list(x.items())", classOf[PyCallable])
    private val hasAttrFn: PyCallable = jep.getValue("hasattr", classOf[PyCallable])

    jep.exec(
      """def __polynote_qualified_type__(o):
        |    if hasattr(o, "__class__"):
        |        if hasattr(o.__class__, "__module__"):
        |            return o.__class__.__module__ + "." + o.__class__.__name__
        |    return type(o).__name__
        |""".stripMargin)

    private val qualifiedTypeFn: PyCallable = jep.getValue("__polynote_qualified_type__", classOf[PyCallable])

    def typeName(obj: PyObject): String = typeFn.callAs(classOf[PyObject], obj).getAttr("__name__", classOf[String])
    def qualifiedTypeName(obj: PyObject): String = qualifiedTypeFn.callAs(classOf[String], obj)
    def len(obj: PyObject): Int = lenFn.callAs(classOf[java.lang.Number], obj).intValue()
    def list(obj: PyObject): PyObject = listFn.callAs(classOf[PyObject], obj)
    def dictToItemsList(obj: PyObject): PyObject = dictToItemsListFn.callAs(classOf[PyObject], obj)
    def hasAttr(obj: PyObject, name: String): Boolean = hasAttrFn.callAs(classOf[java.lang.Boolean], obj, name).booleanValue()
  }


  private[python] def jepExecutor(jepThread: AtomicReference[Thread])(classLoader: ClassLoader): UIO[Executor] = ZIO.effectTotal {
    Executor.fromExecutionContext(Int.MaxValue) {
      ExecutionContext.fromExecutorService {
        Executors.newSingleThreadExecutor {
          new ThreadFactory {
            def newThread(r: Runnable): Thread = {
              val thread = new Thread(r)
              thread.setName("Python interpreter thread")
              thread.setDaemon(true)
              thread.setContextClassLoader(classLoader)
              if (!jepThread.compareAndSet(null, thread)) {
                throw new IllegalStateException("Python interpreter thread died; can't replace it with a new one")
              }
              thread
            }
          }
        }
      }
    }
  }

  // TODO: pull this from configuration?
  private[python] def sharedModules: List[String] = List("numpy", "google")

  private[python] def mkJep(venv: Option[Path], sharedModules: List[String]): RIO[ScalaCompiler.Provider, Jep] = ZIO.accessM[ScalaCompiler.Provider](_.scalaCompiler.classLoader).flatMap {
    classLoader => ZIO {
      val conf = new JepConfig()
        .addSharedModules(sharedModules: _*)
        .setClassLoader(classLoader)
        .setClassEnquirer(new NamingConventionClassEnquirer(true).addTopLevelPackageName("polynote"))
      val interp = new SubInterpreter(conf)
      venv.foreach(path => interp.exec(s"""exec(open("$path/bin/activate_this.py").read(), {'__file__': "$path/bin/activate_this.py"})"""))
      interp
    }
  }

  private[python] def mkJepBlocking(jepExecutor: Executor) = new Blocking {
    val blocking: Blocking.Service[Any] = new Blocking.Service[Any] {
      def blockingExecutor: ZIO[Any, Nothing, Executor] = ZIO.succeed(jepExecutor)
    }
  }

  def apply(
    venv: Option[Path],
    sharedModules: List[String] = PythonInterpreter.sharedModules,
    py4jError: String => Option[Throwable] = _ => None
  ): RIO[ScalaCompiler.Provider, PythonInterpreter] = {
    val jepThread = new AtomicReference[Thread](null)
    for {
      compiler <- ZIO.access[ScalaCompiler.Provider](_.scalaCompiler)
      executor <- compiler.classLoader >>= jepExecutor(jepThread)
      jep      <- mkJep(venv, sharedModules).lock(executor)
      blocking  = mkJepBlocking(executor)
      api      <- effectBlocking(new PythonAPI(jep)).lock(executor).provide(blocking)
      runtime  <- ZIO.runtime[Any]
    } yield new PythonInterpreter(compiler, jep, executor, jepThread, blocking, runtime, api, venv, py4jError)
  }

  object Factory extends Interpreter.Factory {
    def languageName: String = "Python"
    def apply(): RIO[Blocking with Config with ScalaCompiler.Provider with CurrentNotebook with CurrentTask with TaskManager, Interpreter] = for {
      venv   <- VirtualEnvFetcher.fetch()
      interp <- PythonInterpreter(venv)
    } yield interp
  }

}

object VirtualEnvFetcher {

  import scala.sys.process._

  private def sanitize(path: String) = path.replace(' ', '_')

  def fetch(): ZIO[TaskManager with Blocking with CurrentNotebook with CurrentTask with Config, Throwable, Option[Path]] = for {
    config   <- Config.access
    notebook <- CurrentNotebook.get
    dirOpt   <- buildVirtualEnv(config, notebook)
  } yield dirOpt

  private def buildVirtualEnv(config: PolynoteConfig, notebook: Notebook) = {
    val notebookConfig = notebook.config.getOrElse(NotebookConfig.empty)
    val dependencies = notebookConfig.dependencies.toList.flatMap(_.getOrElse("python", Nil))
    if (dependencies.nonEmpty) {
      for {
        dir <- effectBlocking(Paths.get(sanitize(config.storage.cache), sanitize(notebook.path), "venv").toAbsolutePath)
        _   <- CurrentTask.update(_.progress(0.0, Some("Creating virtual environment")))
        _   <- initVenv(dir)
        _   <- CurrentTask.update(_.progress(0.2, Some("Installing dependencies")))
        _   <- installDependencies(dir, notebookConfig.repositories.toList.flatten, dependencies, notebookConfig.exclusions.toList.flatten)
      } yield Some(dir)
    } else ZIO.succeed(None)
  }

  private def initVenv(path: Path) = effectBlocking(path.toFile.exists()).flatMap {
    case true  => ZIO.unit
    case false => effectBlocking {
      Seq("virtualenv", "--system-site-packages", "--python=python3", path.toString).!
    }.unit
  }

  private def installDependencies(
    venv: Path,
    repositories: List[config.RepositoryConfig],
    dependencies: List[String],
    exclusions: List[String]
  ): RIO[TaskManager with Blocking with CurrentTask, Unit] = {

    val options: List[String] = repositories.collect {
      case pip(url) => Seq("--extra-index-url", url)
    }.flatten

    def pip(action: String, dep: String, extraOptions: List[String] = Nil): RIO[Blocking, Unit] = {
      val baseCmd = List(s"$venv/bin/pip", action)
      val cmd = baseCmd ::: options ::: extraOptions ::: dep :: Nil
      effectBlocking(cmd.!)
    }


    CurrentTask.access.flatMap {
      task =>
        val total = dependencies.size
        val depProgress = 0.5 / dependencies.size
        dependencies.map {
          dep =>
            task.update(task => task.copy(label = dep).progress(task.progressFraction + depProgress)) *>
            pip("install", dep) *>
            pip("download", dep, List("--dest", s"$venv/deps/"))
        }.sequence.unit
    }
  }

}
