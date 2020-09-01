package polynote.kernel.interpreter
package python
import java.nio.file.{Files, Path, Paths}
import java.util
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.concurrent.{Executors, LinkedBlockingQueue, ThreadFactory}

import cats.syntax.traverse._
import cats.instances.list._
import jep.python.{PyCallable, PyObject}
import jep.{Jep, JepConfig, JepException, MainInterpreter, NamingConventionClassEnquirer, SharedInterpreter, SubInterpreter}
import polynote.config
import polynote.config.{PolynoteConfig, pip}
import polynote.kernel.environment.{Config, CurrentNotebook, CurrentRuntime, CurrentTask}
import polynote.kernel.task.TaskManager
import polynote.kernel.{CompileErrors, Completion, CompletionType, InterpreterEnv, KernelReport, ParameterHint, ParameterHints, Pos, ResultValue, ScalaCompiler, Signatures}
import polynote.messages.{CellID, Notebook, NotebookConfig, ShortString, TinyList, TinyString}
import polynote.runtime.python.{PythonFunction, PythonObject, TypedPythonObject}
import zio.internal.{ExecutionMetrics, Executor}
import zio.blocking.{Blocking, effectBlocking}
import zio.{Has, RIO, Runtime, Task, UIO, ZIO}
import zio.interop.catz._

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext
import scala.reflect.{ClassTag, classTag}
import scala.util.Try

class PythonInterpreter private[python] (
  val compiler: ScalaCompiler,
  jepInstance: Jep,
  jepExecutor: Executor,
  jepThread: AtomicReference[Thread],
  jepBlockingService: Blocking,
  runtime: Runtime[Any],
  pyApi: PythonInterpreter.PythonAPI,
  venvPath: Option[Path]
) extends Interpreter {
  import pyApi._

  private val runner: PythonObject.Runner = new PythonObject.Runner {
    def run[T](task: => T): T = if (Thread.currentThread() eq jepThread.get()) {
      task
    } else {
      runtime.unsafeRun(effectBlocking(task).lock(jepExecutor).provide(jepBlockingService))
    }

    override def runJep[T](task: Jep => T): T = if (Thread.currentThread() eq jepThread.get()) {
      task(jepInstance)
    } else {
      runtime.unsafeRun(effectBlocking(task(jepInstance)).lock(jepExecutor).provide(jepBlockingService))
    }

    def hasAttribute(obj: PythonObject, name: String): Boolean = run {
      hasAttr(obj.unwrap, name)
    }

    def asScalaList(obj: PythonObject): List[PythonObject] = run {
      val pyObj = obj.unwrap
      val getItem = pyObj.getAttr("__getitem__", classOf[PyCallable])
      val length = pyApi.len(pyObj)

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
      val length = pyApi.len(items)
      val tuples = List.tabulate(length)(i => getItem.callAs(classOf[PyObject], Int.box(i)))
      val result = tuples.map {
        tup =>
          val tupleItem = tup.getAttr("__getitem__", classOf[PyCallable])
          tupleItem.callAs(K, Int.box(0)) -> tupleItem.callAs(V, Int.box(1))
      }.toMap
      result
    }

    override def asTuple2(obj: PythonObject): (PythonObject, PythonObject) = run {
      val pyobj = obj.unwrap
      val getItem = pyobj.getAttr("__getitem__", classOf[PyCallable])
      val _1 = new PythonObject(getItem.callAs(classOf[PyObject], Int.box(0)), this)
      val _2 = new PythonObject(getItem.callAs(classOf[PyObject], Int.box(1)), this)
      (_1, _2)
    }

    override def asTuple2Of[A: ClassTag, B: ClassTag](obj: PythonObject): (A, B) = run {
      val pyobj = obj.unwrap
      val getItem = pyobj.getAttr("__getitem__", classOf[PyCallable])
      val _1 = getItem.callAs(classTag[A].runtimeClass.asInstanceOf[Class[A]], Int.box(0))
      val _2 = getItem.callAs(classTag[B].runtimeClass.asInstanceOf[Class[B]], Int.box(1))
      (_1, _2)
    }

    override def typeName(obj: PythonObject): String = run(pyApi.typeName(obj.unwrap))
    override def qualifiedTypeName(obj: PythonObject): String = run(pyApi.qualifiedTypeName(obj.unwrap))
    override def isCallable(obj: PyObject): Boolean = run(pyApi.isCallable(obj))
    override def len(obj: PythonObject): Int = run(pyApi.len(obj.unwrap))
    override def len64(obj: PythonObject): Long = run(pyApi.len64(obj.unwrap))
    override def list(obj: AnyRef): PythonObject = new PythonObject(run(pyApi.list(obj)), this)
    override def listOf(objs: AnyRef*): PythonObject = new PythonObject(run(pyApi.list(objs.map(PythonObject.unwrapArg).toArray)), this)
    override def tupleOf(objs: AnyRef*): PythonObject = new PythonObject(run(pyApi.tuple(pyApi.list(objs.map(PythonObject.unwrapArg).toArray))), this)
    override def dictOf(kvs: (AnyRef, AnyRef)*): PythonObject = new PythonObject(run(pyApi.dictOf(kvs: _*)), this)
    override def str(obj: AnyRef): PythonObject = new PythonObject(run(pyApi.str(obj)), this)
  }

  def run(code: String, state: State): RIO[InterpreterEnv, State] = for {
    cell      <- ZIO.succeed(s"Cell${state.id}")
    parsed    <- parse(code, cell)
    compiled  <- compile(parsed, cell)
    globals   <- populateGlobals(state)
    _         <- injectGlobals(globals)
    resState  <- run(compiled, globals, state)
  } yield resState

  private def extractParams(jep: Jep, jediDefinition: PyObject): List[(String, String)] = {
    val getParams = jep.getValue("lambda jediDef: list(map(lambda p: [p.name, next(iter(map(lambda t: t.name, p.infer())), None)], jediDef.params))", classOf[PyCallable])
    getParams.callAs(classOf[java.util.List[java.util.List[String]]], jediDefinition).asScala.map {
      tup =>
        val name = tup.get(0)
        val typeName = if (tup.size > 1) Option(tup.get(1)) else None
        (name, typeName.getOrElse(""))
    }.toList
  }

  def completionsAt(code: String, pos: Int, state: State): Task[List[Completion]] = populateGlobals(state).flatMap {
    globals => jep {
      jep =>
        val jedi = jep.getValue("jedi.Interpreter", classOf[PyCallable])
        val lines = code.substring(0, pos).split('\n')
        val lineNo = lines.length
        val col = lines.last.length
        val pyCompletions = jedi.callAs[PyObject](classOf[PyObject], code, Array(globals))
          .getAttr("complete", classOf[PyCallable])
          .callAs(classOf[Array[PyObject]], Integer.valueOf(lineNo), Integer.valueOf(col))

        pyCompletions.map {
          completion =>
            val name = completion.getAttr("name", classOf[String])
            val typ = completion.getAttr("type", classOf[String])
            // TODO: can we get better type completions?
            val params = typ match {
              case "function" =>
                List(TinyList(extractParams(jep, completion).map { case (a, b) => (TinyString(a), ShortString(b))}))
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
          val sig = jedi.callAs[PyObject](classOf[PyObject], code, Array(globals))
            .getAttr("get_signatures", classOf[PyCallable])
            .callAs(classOf[Array[PyObject]], Integer.valueOf(line), Integer.valueOf(col))
            .head

          val index = sig.getAttr("index", classOf[java.lang.Long])

          val params = extractParams(jep, sig).map {
            case (name, typeName) =>
              ParameterHint(name, typeName, None) // TODO: can we parse per-param docstrings out of the main docstring?
          }

          val docString = Try(sig.getAttr("docstring", classOf[PyCallable]).callAs(classOf[String], java.lang.Boolean.TRUE))
            .map(_.toString.split("\n\n").head)
            .toOption.filterNot(_.isEmpty).map(ShortString.truncate)

          val name = s"${sig.getAttr("name", classOf[String])}(${params.mkString(", ")})"
          val hints = ParameterHints(
            name,
            docString,
            params
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
    globals <- getValue("globals().copy()")
    _       <- exec(setup)
    _       <- exec(matplotlib)
    scope   <- populateGlobals(state)
    _       <- jep { _ =>
      val update = globals.getAttr("update", classOf[PyCallable])
      update.call(scope)
    }
  } yield PythonState(state.id, state.prev, Nil, globals)

  def shutdown(): Task[Unit] = jep {
    jep =>
      // We need to run any registered Python exit hooks before closing the interpreter, because Jep doesn't seem be
      // running them. See https://github.com/polynote/polynote/issues/759
      // When running multiple interpreters in the same JVM (using KernelIsolation=Never) this may cause bad things to
      // happen (since atexit is shared across all interpreters). Luckily we only run in this mode when debugging so
      // this shouldn't cause problems for users.
      // Please see here for more information: https://github.com/ninia/jep/issues/225#issuecomment-578376803
      jep.exec(
        """
          |import atexit
          |atexit._run_exitfuncs()
          |""".stripMargin)
      jep.close()
  }

  protected[python] def jep[T](fn: Jep => T): Task[T] = effectBlocking(fn(jepInstance)).lock(jepExecutor).provide(jepBlockingService)
  protected[python] def exec(code: String): Task[Unit] = jep(_.exec(code))
  protected[python] def eval[T : ClassTag](code: String): Task[T] = jep(_.getValue(code, classTag[T].runtimeClass.asInstanceOf[Class[T]]))
  protected[python] def setValue(name: String, value: AnyRef): Task[Unit] = jep(_.set(name, value))
  protected[python] def getValue(name: String): Task[PyObject] = jep(_.getValue(name, classOf[PyObject]))


  protected def setup: String =
    """
      |# This import enables postponed evaluation of annotations, which is needed for data classes and other type hints
      |# to work properly. For more details, see: https://www.python.org/dev/peps/pep-0563/
      |from __future__ import annotations
      |try:
      |    import os, sys, ast, jedi, shutil
      |    from pathlib import Path
      |    from importlib.util import spec_from_loader
      |    from collections import defaultdict, UserDict
      |
      |    if not hasattr(sys, 'argv') or len(sys.argv) == 0:
      |        sys.argv  = ['']
      |
      |    class DelegatingFinder(object):
      |        '''
      |        The DelegatingFinder handles package naming collisions between Python and JVM packages.
      |
      |        When Python searches for a module to import, after checking whether it was already imported in `sys.modules`,
      |        it searches the meta path (`sys.meta_path`), which contains a list of meta path finder objects. When
      |        searching for a module, for example `foo.bar.baz`, Python will first iterate through the meta path,
      |        querying each finder for whether it handles `foo`.
      |
      |        Having found that top-level finder, it will use that finder to request submodules of that top-level path.
      |        Thus, the finder that "owns" `foo` will be used to import `foo.bar` and `foo.bar.baz`. If the finder
      |        can't find a submodule (say, `foo.bar.blahblah`), Python will then go back and search the other finder on
      |        the meta path.
      |
      |        Unfortunately, when searching for a submodule, Python provides a search path to the finders which assumes
      |        that the submodule can be found in the same location as its parent module: that `foo.bar.baz` can be found
      |        relative to where `foo.bar` is. Unfortunately, this is assumption breaks when different finders need to
      |        be used to import packages which share package prefixes.
      |
      |            For example, consider a python package `com.mycompany.pythonthing`, and a Java package
      |            `com.mycompany.javathing`. Depending on which was imported first, the "owner" of `com.mycompany` is either
      |            the Jep finder or one of the default Python finders.
      |
      |            If the Java package was imported first, the `com.mycompany` search path will be Java-specific: the Jep
      |            finder sets this to `None` since it doesn't use the search path.
      |            If the Python package was imported first, the `com.mycompany` search path will be Python-specific:
      |            typically something like `['/path/to/python/lib/python3.7/site-packages/com/mycompany/']`.
      |
      |            This leads to a conflict, because the Python finders can't find `com.mycompany.pythonthing` when they
      |            don't have a search path to the _Python_ `com.mycompany` module.
      |
      |        This is where DelegatingFinder comes in. It hijacks the importing process by conducting its own module
      |        search on the meta path - and thus can ensure that the search path for submodules is generated by walking
      |        the package parts _for the specific finder_.
      |
      |            Continuing our example, when `com.mycompany.pythonthing` is requests and the Jep finder can't find it,
      |            DelegatingFinder will search the Python finders _from the top_ to see whether they can handle it.
      |            This ensures that the proper search path will be used for `com`, `com.mycompany`, and finally
      |            `com.mycompany.pythonthing`.
      |
      |        This ensures that multiple finders can be supported for each package prefix.
      |
      |        Note that if the package names are exactly the same for Python and Java, the Java package is used.
      |        This can be made configurable at a later date if needed.
      |
      |        For more information about Python importing and module loading, see:
      |            https://docs.python.org/3.7/reference/import.html#the-meta-path
      |            https://docs.python.org/3.7/library/importlib.html#importlib.machinery.ModuleSpec (and the rest of `importlib`.)
      |        '''
      |
      |        def __init__(self):
      |            # path -> finder
      |            self.imported = defaultdict(list)
      |
      |        def split_finders(self):
      |            notThis = lambda finder: "DelegatingFinder" not in finder.__class__.__name__
      |            jep_finder = list(filter(lambda p: notThis(p) and "Jep" in str(p), sys.meta_path))[0]
      |            other_finders = filter(lambda p: notThis(p) and "Jep" not in str(p), sys.meta_path)
      |            return jep_finder, other_finders
      |
      |        def find(self, finder, fullname, path, target):
      |            try:
      |                if hasattr(finder, 'find_spec'):
      |                    return finder.find_spec(fullname, path, target)
      |                else:
      |                    loader = finder.find_module(fullname, path)
      |                    if loader:
      |                        spec = spec_from_loader(fullname, loader)
      |                        return spec
      |                    else:
      |                        return None
      |            except:
      |                return None
      |
      |        def find_spec(self, fullname, path, target=None):
      |            # first, if this is a submodule we attempt to shortcut directly to the loader that loaded its parent
      |            try:
      |                parent = ".".join(fullname.split(".")[:-1])
      |                for finder in self.imported.get(parent, []):
      |                    spec = self.find(finder, fullname, path, target)
      |                    if spec:
      |                        self.imported[fullname].append(finder)
      |                        return spec
      |            except:
      |                pass
      |
      |            jep, others = self.split_finders()
      |
      |            for other in others:
      |                spec = self.find(other, fullname, path, target)
      |                if spec:
      |                    self.imported[fullname].append(other)
      |                    return spec
      |                else:
      |                    # maybe the path is from a different branch (different finder)
      |                    # lets try importing with this finder from the top
      |                    parts = fullname.split(".")
      |                    p = None
      |                    spec = None
      |                    for idx, part in enumerate(parts):
      |                        parent = ".".join(parts[0:idx + 1])
      |                        spec = self.find(other, parent, p, None) # TODO: what should target be?
      |                        if spec:
      |                            self.imported[parent].append(other)
      |                            if hasattr(spec, 'submodule_search_locations'):
      |                                p = spec.submodule_search_locations
      |
      |                    if spec:
      |                        self.imported[fullname].append(other)
      |                        return spec
      |
      |            # Deprioritize the jep finder - if there's a direct conflict, we want python imports to take priority.
      |            spec = self.find(jep, fullname, path, target)
      |            if spec:
      |                # self.imported[fullname].append(jep)  # We don't add Jep-imported modules here because Jep lies!
      |                return spec
      |
      |            return None
      |
      |
      |    # Put our finder at the front of the path so it's top priority (making sure it only happens once)
      |    if all("DelegatingFinder" not in f.__class__.__name__ for f in sys.meta_path):
      |        sys.meta_path.insert(0, DelegatingFinder())
      |
      |    # Now it's safe to use jep to import things
      |    from java.lang import RuntimeException, StackTraceElement
      |    from java.util import ArrayList, HashMap
      |
      |    from polynote.kernel import Pos
      |    from polynote.kernel import KernelReport
      |
      |    class LastExprAssigner(ast.NodeTransformer):
      |
      |        # keep track of last line of initial tree passed in
      |        lastLine = None
      |
      |        def visit(self, node):
      |            if (not self.lastLine):
      |                if (len(node.body) == 0):
      |                    return node
      |                self.lastLine = node.body[-1].lineno
      |            return super(ast.NodeTransformer, self).visit(node)
      |
      |        def visit_Expr(self, node):
      |            if node.lineno == self.lastLine:
      |                return ast.copy_location(ast.Assign(targets=[ast.Name(id='Out', ctx=ast.Store())], value=node.value), node)
      |            else:
      |                return node
      |
      |    class TrackingNamespace(UserDict, dict):
      |        '''
      |        A special namespace for Python 3 exec() that provides access to globals while tracking variables added or
      |        updated in the local namespace.
      |
      |        The global namespace is stored in `globals`.
      |        The local namespace is stored in `locals`.
      |
      |        Why this is necessary:
      |
      |            When we run user code from a Python cell, we need to provide it access to the global namespace (builtins,
      |            variables defined in other cells, etc). We also need to keep track of any modifications to the namespace
      |            made as a result of executing the code (say, the definition/modification of a variable).
      |
      |            It would seem that the correct way to do this would be to call `exec` with all three parameters - the code,
      |            the globals dictionary, and an empty locals dictionary to capture the execution results.
      |
      |            In fact, that is what we used to do, until we ran into this:
      |
      |               If exec gets two separate objects as globals and locals, the code will be executed as if it were embedded
      |               in a class definition
      |               (from https://docs.python.org/3/library/functions.html#exec)
      |
      |            Unfortunately, scoping acts a little strangely in class scopes:
      |
      |               Names in class scope are not accessible. Names are resolved in the innermost enclosing function scope. If
      |               a class definition occurs in a chain of nested scopes, the resolution process skips class definitions.
      |               (from https://www.python.org/dev/peps/pep-0227/#discussion)
      |
      |            See also this excellent post https://stackoverflow.com/a/13913933 for more details.
      |
      |            The solution to all this is "don't pass in two dictionaries to exec".
      |
      |            So, we need another way to provide a single dictionary that can provide the global namespace while still
      |            tracking the execution result.
      |
      |            Luckily, someone else had the same problem, so I was able to grab this class from their solution, which
      |            can be found at https://stackoverflow.com/a/48720150
      |        '''
      |
      |
      |        def __init__(self, initial, *args, **kw):
      |            UserDict.__init__(self, *args, **kw)
      |            self.globals = initial
      |
      |        def __getitem__(self, key):
      |            try:
      |                return self.data[key]
      |            except KeyError:
      |                return self.globals[key]
      |
      |        def __contains__(self, key):
      |            return key in self.data or key in self.globals
      |
      |    def __polynote_mkindex__(l1, l2):
      |        import pandas as pd
      |        return pd.MultiIndex.from_arrays([[x for x in list(l1)], [x for x in list(l2)]])
      |
      |    def __polynote_parse__(code, cell):
      |        try:
      |            return { 'result': ast.fix_missing_locations(LastExprAssigner().visit(ast.parse(code, cell, 'exec'))) }
      |        except SyntaxError as err:
      |            lines = code.splitlines(True)
      |            pos = sum([len(lines[x]) for x in range(0, err.lineno - 1)])
      |            pos = pos + err.offset
      |            return { 'error': KernelReport(Pos(cell, pos, pos, pos), err.msg, 2) }
      |
      |    def __polynote_compile__(parsed, cell):
      |        # Python 3.8 compat, see https://github.com/ipython/ipython/pull/11593/files#diff-1c766d4a0b1ea9ed8b2d14058b8234ab
      |        if sys.version_info > (3,8):
      |            from ast import Module
      |        else :
      |            # mock the new API, ignore second argument
      |            # see https://github.com/ipython/ipython/issues/11590
      |            from ast import Module as OriginalModule
      |            Module = lambda nodelist, ignored: OriginalModule(nodelist)
      |
      |        result = []
      |        for tree in parsed.body:
      |            compiled = compile(Module([tree], []), cell, 'exec')
      |            isImport = isinstance(tree, ast.Import) or isinstance(tree, ast.ImportFrom)
      |            result.append([compiled, isImport])
      |        return result
      |
      |    def __polynote_run__(compiled, globals, kernel):
      |        try:
      |            sys.stdout = kernel.display
      |            tracking_ns = TrackingNamespace(globals)
      |            for stat, isImport in compiled:
      |                if isImport: # don't track locals if the tree is an import.
      |                    exec(stat, tracking_ns.globals)
      |                else:
      |                    exec(stat, tracking_ns)
      |                    tracking_ns.globals.update(tracking_ns.data)
      |
      |            types = { x: type(y).__name__ for x,y in tracking_ns.data.items() }
      |            return { 'globals': tracking_ns.globals, 'locals': tracking_ns.data, 'types': types }
      |        except Exception as err:
      |            import traceback
      |
      |            def handle_exception(typ, err_val, tb):
      |                trace = ArrayList()
      |                for frame in traceback.extract_tb(tb):
      |                    trace.add(StackTraceElement(frame.filename.split("/")[-1], frame.name, frame.filename, frame.lineno))
      |                result = {
      |                  'stack_trace': trace,
      |                  'message': getattr(err_val, 'message', str(err_val)),
      |                  'class': typ.__name__,
      |                  'err': err_val
      |                }
      |
      |                # Lifted from IPython.core.ultratb
      |                def get_chained_exception(exception_value):
      |                    cause = getattr(exception_value, '__cause__', None)
      |                    if cause:
      |                        return cause
      |                    if getattr(exception_value, '__suppress_context__', False):
      |                        return None
      |                    return getattr(exception_value, '__context__', None)
      |
      |                cause = get_chained_exception(err_val)
      |                if cause:
      |                    result['cause'] = handle_exception(type(cause), cause, cause.__traceback__)
      |
      |                return result
      |
      |            return handle_exception(type(err), err, err.__traceback__)
      |except Exception as e:
      |    import sys, traceback
      |    print("An error occurred while initializing the Python interpreter.", e, file=sys.stderr)
      |    traceback.print_exc(file=sys.stderr)
      |    sys.stderr.flush()
      |    raise e
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
      // grab the nearest Python state (if any) so we can use its globals dict.
      val maybePrevPyState = state.takeUntil(_.isInstanceOf[PythonState]).reverse
      val globalsDict = maybePrevPyState match {
        case PythonState(_, _, _, globalsDict) :: _ => globalsDict.getAttr("copy", classOf[PyCallable]).callAs(classOf[PyObject])
        case _                                      => jep.getValue("{}", classOf[PyObject])
      }

      val addGlobal = globalsDict.getAttr("__setitem__", classOf[PyCallable])

      val convert = convertToPython(jep) orElse PartialFunction(defaultConvertToPython)

      state.scope.reverse.map(v => v.name -> v.value).foreach {
        case nv@(name, value) => addGlobal.call(name, convert(nv))
      }

      globalsDict
  }

  protected def defaultConvertToPython(nv: (String, Any)): AnyRef = nv._2.asInstanceOf[AnyRef]

  protected def convertToPython(jep: Jep): PartialFunction[(String, Any), AnyRef] = {
    case (_, value: PythonObject) => value.unwrap
  }

  protected def convertFromPython(jep: Jep): PartialFunction[(String, PyObject), (compiler.global.Type, Any)] =
    PartialFunction.empty

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

  protected def compile(parsed: PyObject, cell: String): Task[PyObject] = jep {
    jep =>
      val compile = jep.getValue("__polynote_compile__", classOf[PyCallable])
      compile.callAs(classOf[PyObject], parsed, cell)
  }

  protected def run(compiled: PyObject, globals: PyObject, state: State): RIO[CurrentRuntime, State] =
    CurrentRuntime.access.flatMap {
      kernelRuntime => jep {
        jep =>
          val run = jep.getValue("__polynote_run__", classOf[PyCallable])
          val result = run.callAs(classOf[PyObject], compiled, globals, kernelRuntime)
          val get = result.getAttr("get", classOf[PyCallable])
          val convert = convertFromPython(jep)
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

                      // TODO: can we get better type information from `PyJArray`?
                      // Types that start with "PyJ*" are actually Java values wrapped by jep for use in Python
                      case other if other.startsWith("PyJ") =>
                        val jValue = valueAs(classOf[Object])
                        val typ = runtime.unsafeRun(compiler.reflect(jValue)).symbol.info
                        (typ, jValue)
                      case other =>
                        val pyObj = valueAs(classOf[PyObject])
                        if (convert.isDefinedAt((typeStr, pyObj))) {
                          convert((typeStr, pyObj))
                        } else {
                          // should we use the qualified type? It's confusing that both Spark and Pandas have a "DataFrame".
                          // But in every other case it's just noise.
                          val typ = appliedType(typeOf[TypedPythonObject[Nothing]].typeConstructor, compiler.global.internal.constantType(Constant(other)))
                          (typ, new TypedPythonObject[String](valueAs(classOf[PyObject]), runner))
                        }
                    }
                    Some(new ResultValue(key, compiler.unsafeFormatType(typ.asInstanceOf[Type]), Nil, state.id, value, typ.asInstanceOf[Type], None))
                  } else None
                } else None
              }.toList.flatten
              PythonState(state.id, state.prev, resultValues.toList, globals)

            case trace =>
              throw handlePyError(get, trace)
          }
      }
    }

  def handlePyError(get: PyCallable, trace: util.ArrayList[Object]): Throwable = {
    val message = get.callAs(classOf[String], "message")
    val typ = get.callAs(classOf[String], "class")
    val cause = errorCause(get)
    val err = cause.fold(new RuntimeException(s"$typ: $message"))(new RuntimeException(s"$typ: $message", _))
    val stackTraceElements = trace.asScala.map(_.asInstanceOf[StackTraceElement]).reverse.toArray
    err.setStackTrace(stackTraceElements)
    err
  }

  protected def errorCause(get: PyCallable): Option[Throwable] =
    Option(get.callAs(classOf[PyObject], "cause")).flatMap {
      cause =>
        val causeGet = cause.getAttr("get", classOf[PyCallable])
        Option(causeGet.callAs(classOf[util.ArrayList[Object]], "stack_trace")).map {
          elements =>
            handlePyError(causeGet, elements)
        }
    }

  case class PythonState(id: CellID, prev: State, values: List[ResultValue], globalsDict: PyObject) extends State {
    override def withPrev(prev: State): State = copy(prev = prev)
    override def updateValues(fn: ResultValue => ResultValue): State = copy(values = values.map(fn))
    override def updateValuesM[R](fn: ResultValue => RIO[R, ResultValue]): RIO[R, State] =
      ZIO.collectAll(values.map(fn)).map(values => copy(values = values))
  }
}

object PythonInterpreter {

  private[python] class PythonAPI(jep: Jep) {
    import PythonObject.unwrapArg
    private val typeFn: PyCallable = jep.getValue("type", classOf[PyCallable])
    private val lenFn: PyCallable = jep.getValue("len", classOf[PyCallable])
    private val listFn: PyCallable = jep.getValue("list", classOf[PyCallable])
    private val tupleFn: PyCallable = jep.getValue("tuple", classOf[PyCallable])
    private val dictFn: PyCallable = jep.getValue("dict", classOf[PyCallable])
    private val zipFn: PyCallable = jep.getValue("zip", classOf[PyCallable])
    private val dictToItemsListFn: PyCallable = jep.getValue("lambda x: list(x.items())", classOf[PyCallable])
    private val hasAttrFn: PyCallable = jep.getValue("hasattr", classOf[PyCallable])
    private val strFn: PyCallable = jep.getValue("str", classOf[PyCallable])

    jep.exec(
      """def __polynote_qualified_type__(o):
        |    if hasattr(o, "__class__"):
        |        if hasattr(o.__class__, "__module__"):
        |            return o.__class__.__module__ + "." + o.__class__.__name__
        |    return type(o).__name__
        |""".stripMargin)

    private val qualifiedTypeFn: PyCallable = jep.getValue("__polynote_qualified_type__", classOf[PyCallable])

    private val callableFn: PyCallable = jep.getValue("callable", classOf[PyCallable])

    def typeName(obj: PyObject): String = typeFn.callAs(classOf[PyObject], obj).getAttr("__name__", classOf[String])
    def qualifiedTypeName(obj: PyObject): String = qualifiedTypeFn.callAs(classOf[String], obj)
    def isCallable(obj: PyObject): Boolean = callableFn.callAs(classOf[java.lang.Boolean], obj).booleanValue()
    def len(obj: PyObject): Int = lenFn.callAs(classOf[java.lang.Number], obj).intValue()
    def len64(obj: PyObject): Long = lenFn.callAs(classOf[java.lang.Number], obj).longValue()
    def list(obj: AnyRef): PyObject = listFn.callAs(classOf[PyObject], unwrapArg(obj))
    def tuple(obj: AnyRef): PyObject = tupleFn.callAs(classOf[PyObject], unwrapArg(obj))
    def dictToItemsList(obj: PyObject): PyObject = dictToItemsListFn.callAs(classOf[PyObject], obj)
    def dictOf(kvs: (AnyRef, AnyRef)*): PyObject = {
      val (ks, vs) = kvs.unzip
      dict(zip(ks.map(unwrapArg).toArray, vs.map(unwrapArg).toArray))
    }
    def dict(obj: AnyRef): PyObject = dictFn.callAs(classOf[PyObject], unwrapArg(obj))
    def zip(list1: AnyRef, list2: AnyRef): PyObject = zipFn.callAs(classOf[PyObject], unwrapArg(list1), unwrapArg(list2))
    def str(obj: AnyRef): PyObject = strFn.callAs(classOf[PyObject], unwrapArg(obj))
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

  private[python] def mkJep(venv: Option[Path]): RIO[ScalaCompiler.Provider, Jep] = ZIO.access[ScalaCompiler.Provider](_.get.classLoader).flatMap {
    classLoader => ZIO {
      val conf = new JepConfig()
        .setClassLoader(classLoader)
        .setClassEnquirer(new NamingConventionClassEnquirer(true).addTopLevelPackageName("polynote"))
//        .setClassEnquirer(ClassList.getInstance())  // NOTE: this seems to break imports of `polynote` packages in our distribution. Not sure why.

      try {
        SharedInterpreter.setConfig(conf)
      } catch  {
        case e: JepException => // we can only set the SharedInterpreter config once, but there's no way to tell if we've already set it :\
      }

      val interp = new SharedInterpreter()
      venv.foreach(path => interp.exec(s"""exec(open("$path/bin/activate_this.py").read(), {'__file__': "$path/bin/activate_this.py"})"""))
      interp
    }
  }

  private[python] def mkJepBlocking(jepExecutor: Executor): Blocking = Has {
    new Blocking.Service {
      override def blockingExecutor: Executor = jepExecutor
    }
  }

  def interpreterDependencies(venv: Option[Path]): ZIO[ScalaCompiler.Provider, Throwable, (ScalaCompiler, Jep, Executor, AtomicReference[Thread], Blocking, Runtime[Any], PythonAPI)] = {
    val jepThread = new AtomicReference[Thread](null)
    for {
      compiler <- ScalaCompiler.access
      executor <- jepExecutor(jepThread)(compiler.classLoader)
      jep      <- mkJep(venv).lock(executor)
      blocking  = mkJepBlocking(executor)
      api      <- effectBlocking(new PythonAPI(jep)).lock(executor).provide(blocking)
      runtime  <- ZIO.runtime[Any]
    } yield (compiler, jep, executor, jepThread, blocking, runtime, api)
  }

  def apply(): RIO[Blocking with Config with ScalaCompiler.Provider with CurrentNotebook with CurrentTask with TaskManager, PythonInterpreter] = {
    for {
      venv    <- VirtualEnvFetcher.fetch()
      interp  <- PythonInterpreter(venv)
    } yield interp
  }

  def apply(venv: Option[Path]): RIO[ScalaCompiler.Provider, PythonInterpreter] = {
    for {
      (compiler, jep, executor, jepThread, blocking, runtime, api) <- interpreterDependencies(venv)
    } yield new PythonInterpreter(compiler, jep, executor, jepThread, blocking, runtime, api, venv)
  }

  object Factory extends Interpreter.Factory {
    def languageName: String = "Python"
    def apply(): RIO[Blocking with Config with ScalaCompiler.Provider with CurrentNotebook with CurrentTask with TaskManager, Interpreter] = PythonInterpreter()
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
