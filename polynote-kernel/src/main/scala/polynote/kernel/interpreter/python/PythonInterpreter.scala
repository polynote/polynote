package polynote.kernel.interpreter
package python
import cats.syntax.either._
import io.circe._
import io.circe.generic.semiauto._
import io.circe.syntax._
import io.circe.yaml.syntax._
import jep.python.{PyCallable, PyObject}
import jep.{Jep, JepConfig, JepException, NamingConventionClassEnquirer, SharedInterpreter}
import polynote.config
import polynote.config.{PolynoteConfig, pip}
import polynote.kernel.dependency.noCacheSentinel
import polynote.kernel.environment.{Config, CurrentNotebook, CurrentRuntime, CurrentTask}
import polynote.kernel.logging.Logging
import polynote.kernel.task.TaskManager
import polynote.kernel.util.DepsParser.flattenDeps
import polynote.kernel.{BaseEnv, CompileErrors, Completion, CompletionType, GlobalEnv, InterpreterEnv, KernelReport, ParameterHint, ParameterHints, ResultValue, ScalaCompiler, Signatures}
import polynote.messages.{CellID, DefinitionLocation, Notebook, NotebookConfig, ShortString, TinyList, TinyString}
import polynote.runtime.KernelRuntime
import polynote.runtime.python.{PythonFunction, PythonObject, TypedPythonObject}
import zio.ZIO.effect
import zio.blocking.{Blocking, effectBlocking}
import zio.internal.Executor
import zio.{Has, RIO, Runtime, Semaphore, Task, UIO, ZIO}

import java.io.{FileReader, PrintWriter, StringWriter}
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import java.util
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ConcurrentHashMap, ConcurrentSkipListSet, Executors, ThreadFactory}
import scala.collection.JavaConverters._
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

  private val allowedSources = new ConcurrentSkipListSet[String]()

  protected val runner: PythonObject.Runner = new PythonObject.Runner {
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
      try {
        hasAttr(obj.unwrap, name)
      } catch  {
        case _: JepException => false
      }
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

    override def as[T >: Null : ClassTag](obj: PythonObject): T = run {
      obj.unwrap.as(classTag[T].runtimeClass.asInstanceOf[Class[T]])
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
    cell            <- ZIO.succeed(s"Cell${state.id}")
    (parsed, decls) <- parse(code, cell)
    compiled        <- compile(parsed, cell)
    globals         <- populateGlobals(state)
    _               <- injectGlobals(globals)
    resState        <- run(compiled, decls, globals, state)
  } yield resState

  private def extractParamsFast(jep: Jep, jediDefinition: PyObject): List[(String, String)] = {
    val sigs = jediDefinition.getAttr("get_signatures", classOf[PyCallable])
      .callAs(classOf[Array[PyObject]])

    sigs.headOption.toList.flatMap {
      sig => sig.getAttr("params", classOf[Array[PyObject]]).toList.map {
        param =>
          val str = param.getAttr("to_string", classOf[PyCallable]).callAs(classOf[String])
          str.split(':').toList match {
            case first :: rest => (first.trim, rest.mkString(":").trim)
          }
      }
    }
  }

  private def extractParams(jep: Jep, jediDefinition: PyObject): List[(String, String)] = {
    // TODO: this lambda is getting a bit unwieldy.
    val getParams = jep.getValue("lambda jediDef: list(map(lambda p: [p.name, next(iter(map(lambda t: t.name, p.infer())), None)], sum([s.params for s in jediDef.get_signatures()], [])))", classOf[PyCallable])
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
        val (lineNo, col) = posToLineAndColumn(code, pos)
        println(lineNo, col)
        val pyCompletions = jedi.callAs[PyObject](classOf[PyObject], code, Array(globals))
          .getAttr("complete", classOf[PyCallable])
          .callAs(classOf[Array[PyObject]], Integer.valueOf(lineNo), Integer.valueOf(col))
        val result = pyCompletions.map {
          completion =>
            val name = completion.getAttr("name", classOf[String])
            val typ = completion.getAttr("type", classOf[String])
            // TODO: can we get better type completions?
            val params = typ match {
              case "function" => List(TinyList(extractParamsFast(jep, completion).map { case (a, b) => (TinyString(a), ShortString(b))}))
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

        result
    }
  }

  def parametersAt(code: String, pos: Int, state: State): Task[Option[Signatures]] = populateGlobals(state).flatMap {
    globals => jep {
      jep =>
        try {
          val (line, col) = posToLineAndColumn(code, pos)
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

  private def definitionLocations(script: PyObject, line: Int, col: Int) = jep { jep =>
    val pyNames = script.getAttr("goto", classOf[PyCallable]) // see https://jedi.readthedocs.io/en/latest/docs/api.html#jedi.Script.goto
      .callAs(
        classOf[Array[PyObject]],
        Array[Object](Integer.valueOf(line), Integer.valueOf(col)), // positional args
        Map[String, Object]("follow_imports" -> Boolean.box(true), "follow_builtin_imports" -> Boolean.box(true)).asJava)
    pyNames.toList.flatMap {
      jediName =>
        val path = jediName.getAttr("module_path", classOf[String])
        val line = jediName.getAttr("line", classOf[Integer])
        val column = jediName.getAttr("column", classOf[Integer])
        if (path != null)
          Some(DefinitionLocation(path, line, column))
        else None
    }
  }.tap {
    locs => ZIO(locs.foreach(loc => allowedSources.add(loc.uri)))
  }

  // TODO: we probably need to write out prior code cells, and use the jedi Project (https://jedi.readthedocs.io/en/latest/docs/api.html#jedi.Project)
  //        to allow finding references to other cells. This will only find references that are external.
  override def goToDefinition(code: String, pos: Int, state: State): RIO[Blocking with Logging, List[DefinitionLocation]] = {
    for {
      globals     <- populateGlobals(state)
      (line, col)  = posToLineAndColumn(code, pos)
      jedi        <- jep(_.getValue("jedi.Interpreter", classOf[PyCallable]).callAs(classOf[PyObject], code, Array(globals)))
      locations   <- definitionLocations(jedi, line, col)
    } yield locations
  }.catchAllCause(cause => Logging.error(cause).as(Nil))

  override def goToDependencyDefinition(uri: String, pos: Int): RIO[Blocking, List[DefinitionLocation]] = for {
    path        <- effect(new URI(uri).getPath)
    // need this just for line/column. Probably should have just used that for position so it could come from front-end
    codeStr     <- readFile(Paths.get(path))
    (line, col)  = posToLineAndColumn(codeStr, pos)
    script      <- jep(_.getValue("jedi.Script", classOf[PyCallable]).callAs(classOf[PyObject], Map[String, Object]("path" -> path).asJava))
    locations   <- definitionLocations(script, line, col)
  } yield locations

  override def getDependencyContent(uri: String): RIO[Blocking, String] = ZIO(new URI(uri)).flatMap {
    // check that the file is a python file, *and* it's previously been sent to the client as a definition location
    case uri if !uri.getPath.endsWith(".py") || !allowedSources.contains(uri.getPath) => ZIO.fail(new SecurityException(s""))
    case uri => effectBlocking(Files.readAllLines(Paths.get(uri.getPath)).asScala.mkString("\n"))
  }

  def init(state: State): RIO[InterpreterEnv, State] = for {
    globals <- getValue("globals().copy()")
    supportStatus <- isFutureAnnotationsSupported
    _       <- exec(setup(supportStatus))
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


  protected def isFutureAnnotationsSupported: Task[Boolean] =
    jep {
      jep =>
        // https://www.python.org/dev/peps/pep-0563/ require python 3.7
        jep.eval("import sys")
        jep.getValue("sys.version_info >= (3,7)",
          classOf[java.lang.Boolean]
        ).booleanValue
    }
  
  protected def importFutureAnnotations: String =
    """
      |# This import enables postponed evaluation of annotations, which is needed for data classes and other type hints
      |# to work properly. For more details, see: https://www.python.org/dev/peps/pep-0563/
      |from __future__ import annotations
      |""".stripMargin
  
  
  protected def setup(isFutureAnnotationsSupported: Boolean): String =
     (if (isFutureAnnotationsSupported) importFutureAnnotations else "") +
      """
      |try:
      |    import os, sys, ast, jedi, shutil
      |    from pathlib import Path
      |    from importlib.util import spec_from_loader
      |    from collections import defaultdict, UserDict
      |
      |    # Set the recursion limit to 1000 on Python 3.7 to avoid https://bugs.python.org/issue38593
      |    if sys.version_info[:2] == (3,7):
      |        sys.setrecursionlimit(1000)
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
      |    def __get_decl_names__(node):
      |       '''Fetch names declared (including in assignments) in the given ast node'''
      |       if isinstance(node, ast.Assign):
      |           names = []
      |           for target in node.targets:
      |               if isinstance(target, ast.Name):
      |                   names.append(target.id)
      |               elif isinstance(target, ast.Tuple) or isinstance(target, ast.List): # unpacking
      |                   names.extend([name.id for name in target.elts])
      |           return names
      |       elif isinstance(node, ast.AnnAssign) or isinstance(node, ast.AugAssign):
      |           if isinstance(node.target, ast.Name):
      |               return [node.target.id]
      |       elif isinstance(node, ast.FunctionDef) or isinstance(node, ast.ClassDef) or isinstance(node, ast.AsyncFunctionDef):
      |           return [node.name]
      |
      |       return []
      |
      |    def __polynote_parse__(code, cell):
      |        try:
      |            code_ast = LastExprAssigner().visit(ast.parse(code, cell, 'exec'))
      |
      |            # walk ast looking for declarations
      |            # python note: sum(list, []) flattens a list of lists.
      |            all_decls = sum([__get_decl_names__(node) for node in ast.walk(code_ast)], [])
      |
      |            # fix line numbers
      |            parsed = ast.fix_missing_locations(code_ast)
      |
      |            return { 'result': parsed, 'decls': all_decls}
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
      |    def __polynote_run__(compiled, decls, globals, kernel):
      |        '''
      |        Runs compiled user code while keeping track of declarations and managing the global namespace
      |
      |        When we run user code from a Python cell, we need to provide it access to the global namespace (builtins,
      |        variables defined in other cells, etc). We also need to keep track of any modifications to the namespace
      |        made as a result of executing the code (say, the definition/modification of a variable).
      |
      |        It would seem that the correct way to do this would be to call `exec` with all three parameters - the code,
      |        the globals dictionary, and an empty locals dictionary to capture the execution results.
      |
      |        In fact, that is what we used to do, until we ran into this:
      |
      |           If exec gets two separate objects as globals and locals, the code will be executed as if it were embedded
      |           in a class definition
      |           (from https://docs.python.org/3/library/functions.html#exec)
      |
      |        Unfortunately, scoping acts a little strangely in class scopes:
      |
      |           Names in class scope are not accessible. Names are resolved in the innermost enclosing function scope. If
      |           a class definition occurs in a chain of nested scopes, the resolution process skips class definitions.
      |           (from https://www.python.org/dev/peps/pep-0227/#discussion)
      |
      |        See also this excellent post https://stackoverflow.com/a/13913933 for more details.
      |
      |        The solution to all this is "don't pass in two dictionaries to exec".
      |
      |        So, without being able to get exec to provide us with `locals`, how can we do that ourselves?
      |        This is where the `decls` parameter comes in. It is a list of names parsed from user code AST
      |        (in __polynote_parse__) that represent all the declared values (function defs, assignments, etc.) in the code.
      |        We compare the contents of the values in the globals dictionary that correspond to these names before and
      |        after the user code is run to determine which of the names actually refers to added or redefined values
      |        (rather than, say, a value defined within a user code function). This way we ensure that our logic
      |        follows Python's scoping rules (without needing to reimplement them in our parser).
      |
      |        Note: Previously (<=0.4.2), we passed in a custom dictionary implementation that tracked changes. This worked, but
      |        had some serious performance degradations.
      |        '''
      |        try:
      |            sys.stdout = kernel.stdout
      |            sys.stderr = kernel.stderr
      |
      |            # These names already exist in globals, so we keep track of them in case they might be reassignments
      |            # This is needed for proper attribution of declarations to cells, otherwise a shadowed variable could
      |            # wind up getting into the cell state.
      |            possibly_reassigned_decls = {name: globals[name] for name in decls if name in globals}
      |
      |            def is_local(name, globals, possible_reassignments):
      |                '''
      |                Given the `name` of a declaration, determines whether it is "local" (i.e., in `locals()`-equivalent scope).
      |
      |                `globals` is the result of the global scope AFTER execution.
      |                `possible_reassignments` stores the values of any `decls` that were present in the global
      |                                         scope BEFORE execution.
      |
      |                Determines whether a variable is "local" as follows:
      |                - If `name` is not in `globals`, it's definitely not local.
      |                - if `name` IS in `globals` but NOT in `possible_reassignments`, it must be a new local declaration.
      |                - If `name` IS in `possible_reassignments` and NOT referentially equal to `globals[name]`,
      |                it must be a reassignment within local scope.
      |                - If a name IS in `possible_reassignments` and IS referentially equal to `globals[name]`,
      |                it must be a shadowed variable in some inner scope (e.g., `x` in a function definition that's
      |                shadowing and `x` defined in a prior cell), so it's NOT local.
      |                '''
      |                if name not in globals:
      |                    return False
      |                else:
      |                    return name not in possible_reassignments or globals[name] is not possible_reassignments[name]
      |
      |            locals = {}
      |            for stat, isImport in compiled:
      |                if isImport: # don't track locals if the tree is an import.
      |                    exec(stat, globals) # Note: globals is mutated
      |                else:
      |                    exec(stat, globals) # Note: globals is mutated
      |                    local_decls = {name: globals[name] for name in decls if is_local(name, globals, possibly_reassigned_decls)}
      |                    locals.update(local_decls)
      |
      |            types = { x: type(y).__name__ for x,y in locals.items() }
      |            return { 'globals': globals, 'locals': locals, 'types': types }
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
      |
      |    # Used in PandasHandle
      |    def __polynote_mkindex__(l1, l2):
      |        import pandas as pd
      |        return pd.MultiIndex.from_arrays([[x for x in list(l1)], [x for x in list(l2)]])
      |
      |    def get_active_kernel():
      |        '''
      |        Returns the instance of the kernel runtime provided to the cell currently being executed.
      |        The value in `globals` gets set by the interpreter when it runs the cell.
      |        '''
      |        return globals()["kernel"]
      |
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
      |    from matplotlib.backend_bases import (_Backend, FigureCanvasBase, FigureManagerBase)
      |    from matplotlib.backends.backend_agg import _BackendAgg
      |    import io, base64
      |
      |    class PolynoteFigureManager(FigureManagerBase):
      |        def _display(self, mime, body):
      |            get_active_kernel().display.content(mime, body)
      |
      |        def png(self):
      |            # Save figure as PNG for display
      |            buf = io.BytesIO()
      |            self.canvas.figure.savefig(buf, format='png')
      |            buf.seek(0)
      |            return base64.b64encode(buf.getvalue()).decode('utf-8')
      |
      |        def svg(self):
      |            # Save figure as SVG for display
      |            import io
      |            buf = io.StringIO()
      |            self.canvas.figure.savefig(buf, format='svg')
      |            buf.seek(0)
      |            return buf.getvalue()
      |
      |        def show(self):
      |            fmt = PolynoteBackend.output_format
      |            if fmt == 'svg':
      |                self._display("image/svg", self.svg())
      |            elif fmt == 'png':
      |                self._display("image/png", self.png())
      |            else:
      |                print(f"PolynoteBackend: Unknown output format. Accepted values for PolynoteBackend.output_format are 'png' or 'svg' but got '{fmt}'. Defaulting to 'png'.",file=sys.stderr)
      |                sys.stderr.flush()
      |                self._display("image/png", self.png())
      |
      |    class PolynoteFigureCanvas(FigureCanvasBase):
      |        manager_class = PolynoteFigureManager
      |
      |    @_Backend.export
      |    class PolynoteBackend(_BackendAgg):
      |        __module__ = "polynote"
      |
      |        FigureManager = PolynoteFigureManager
      |        FigureCanvas = PolynoteFigureCanvas
      |
      |        output_format = 'png' # options are ['png', 'svg']
      |
      |        @classmethod
      |        def show(cls):
      |            try:
      |                managers = Gcf.get_all_fig_managers()
      |                if not managers:
      |                    return  # this means there's nothing to plot, apparently.
      |                managers[0].show() # ignore other managers (not sure where they come from...?)
      |            finally:
      |                Gcf.destroy_all()
      |                matplotlib.pyplot.close('all')
      |
      |    import matplotlib
      |    matplotlib.use("module://" + PolynoteBackend.__module__)
      |    print("matplotlib backend set to:", matplotlib.get_backend(), file=sys.stderr)
      |    sys.stderr.flush()
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
          jep.set("kernel", runtime)

          // make visible to cell globals
          setItem.call("get_active_kernel", jep.getValue("get_active_kernel"))

          // expose `PolynoteBackend` so users can set `PolynoteBackend.output_format`
          // `PolynoteBackend` is not defined if `matplotlib` is not present.
          Try(jep.getValue("PolynoteBackend", classOf[PyObject])).foreach {
            backend =>
              setItem.call("PolynoteBackend", backend)
          }
      }
  }

  protected def populateGlobals(state: State): Task[PyObject] = jep {
    jep =>
      // collect `globals` from previous states, overriding old entries with newer ones.
      val globalsDict = state.collect {
        case PythonState(_, _, _, globalsDict) => globalsDict
      }.foldLeft(jep.getValue("{}", classOf[PyObject])) {
        (acc: PyObject, next: PyObject) =>
          acc.getAttr("update", classOf[PyCallable]).call(next)
          acc
      }

      val addGlobal = globalsDict.getAttr("__setitem__", classOf[PyCallable])

      val convert = convertToPython(jep)

      state.scope.reverse.map(v => v.name -> v.value).foreach {
        case nv@(name, value) => addGlobal.call(name, convert.applyOrElse(nv, defaultConvertToPython))
      }

      globalsDict
  }

  protected def defaultConvertToPython(nv: (String, Any)): AnyRef = nv._2.asInstanceOf[AnyRef]

  protected def convertToPython(jep: Jep): PartialFunction[(String, Any), AnyRef] = {
    case (_, value: PythonObject) => value.unwrap
  }

  protected def convertFromPython(jep: Jep): PartialFunction[(String, PyObject), (compiler.global.Type, Any)] =
    PartialFunction.empty

  protected def parse(code: String, cell: String): Task[(PyObject, PyObject)] = jep {
    jep =>
      val result = jep.getValue("__polynote_parse__", classOf[PyCallable]).callAs(classOf[PyObject], code, cell)
      val get = result.getAttr("get", classOf[PyCallable])
      (get.callAs(classOf[PyObject], "result"), get.callAs(classOf[PyObject], "decls")) match {
        case (null, _) => get.callAs(classOf[KernelReport], "error") match {
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

  protected def run(compiled: PyObject, decls: PyObject, globals: PyObject, state: State): RIO[CurrentRuntime, State] =
    CurrentRuntime.access.flatMap {
      kernelRuntime => jep {
        jep =>
          val run = jep.getValue("__polynote_run__", classOf[PyCallable])
          val result = run.callAs(classOf[PyObject], compiled, decls, globals, kernelRuntime)
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

                  // filter `Out` values that are equal to `None` (this approximates a "void" return type for Python)
                  val notNoneOut = key != "Out" || (key == "Out" && typeStr != "NoneType")

                  // unwrap a wrapped Java value
                  def unwrapJavaValue = {
                    val jValue = valueAs(classOf[Object])
                    val typ = runtime.unsafeRun(compiler.reflect(jValue)).symbol.info
                    (typ, jValue)
                  }

                  if (typeStr != null && notNoneOut && typeStr != "module") {
                    val (typ, value) = typeStr match {
                      case "int" => (typeOf[Long], valueAs(classOf[java.lang.Number]).longValue())
                      case "float" => (typeOf[Double], valueAs(classOf[java.lang.Number]).doubleValue())
                      case "str" => (typeOf[java.lang.String], valueAs(classOf[String]))
                      case "bool" => (typeOf[Boolean], valueAs(classOf[java.lang.Boolean]).booleanValue())
                      case "function" | "builtin_function_or_method" | "type" =>
                        (typeOf[PythonFunction], new PythonFunction(valueAs(classOf[PyCallable]), runner))

                      // TODO: can we get better type information from `PyJArray`?
                      // Types that start with "PyJ*" are actually Java values wrapped by jep for use in Python
                      case other if other.startsWith("PyJ") => unwrapJavaValue
                      case other =>
                        // TODO: is there any way to determine whether it's a wrapped Java value if it's not wrapped in PyJ*?
                        try {
                          val pyObj = valueAs(classOf[PyObject])
                          if (convert.isDefinedAt((typeStr, pyObj))) {
                            convert((typeStr, pyObj))
                          } else {
                            // should we use the qualified type? It's confusing that both Spark and Pandas have a "DataFrame".
                            // But in every other case it's just noise.
                            val typ = appliedType(typeOf[TypedPythonObject[Nothing]].typeConstructor, compiler.global.internal.constantType(Constant(other)))
                            (typ, new TypedPythonObject[String](valueAs(classOf[PyObject]), runner))
                          }
                        } catch {
                          case e: JepException if e.getMessage.contains("TypeError") =>
                            // this might still be a Java value that for some reason isn't wrapped as a PyJ* object
                            unwrapJavaValue
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

  override val fileExtensions: Set[String] = Set("py")

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
      venv.foreach { path =>
        // activate virtualenv
        interp.exec(s"""exec(open("$path/bin/activate_this.py").read(), {'__file__': "$path/bin/activate_this.py"})""")
        // update pkg_resources with the new venv path entries. I don't understand why virtualenv doesn't do this
        // automatically...
        interp.exec(
          """
            |import sys, pkg_resources
            |diff = set(sys.path).difference(pkg_resources.working_set.entries)
            |for entry in diff:
            |    pkg_resources.working_set.add_entry(entry)
            |""".stripMargin)
      }
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

  def apply(): RIO[BaseEnv with GlobalEnv with ScalaCompiler.Provider with CurrentNotebook with CurrentTask with TaskManager, PythonInterpreter] = {
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
    def apply(): RIO[BaseEnv with GlobalEnv with ScalaCompiler.Provider with CurrentNotebook with CurrentTask with TaskManager, Interpreter] = PythonInterpreter()
  }

}

final case class PythonDepConfig(
  dependencies: List[String],
  repositories: List[config.RepositoryConfig],
  exclusions: List[String]
)
object PythonDepConfig {
  implicit val encoder: Encoder[PythonDepConfig] = deriveEncoder[PythonDepConfig]
  implicit val decoder: Decoder[PythonDepConfig] = deriveDecoder[PythonDepConfig]
}

object VirtualEnvFetcher {

  import scala.sys.process._

  private def sanitize(path: String) = path.replace(' ', '_')

  def fetch(): ZIO[BaseEnv with GlobalEnv with ScalaCompiler.Provider with CurrentNotebook with CurrentTask with TaskManager, Throwable, Option[Path]] = for {
    config   <- Config.access
    notebook <- CurrentNotebook.get
    dirOpt   <- buildVirtualEnv(config, notebook)
  } yield dirOpt

  private def buildVirtualEnv(config: PolynoteConfig, notebook: Notebook): ZIO[BaseEnv with GlobalEnv with ScalaCompiler.Provider with CurrentNotebook with CurrentTask with TaskManager, Throwable, Option[Path]] = {
    val notebookConfig = notebook.config.getOrElse(NotebookConfig.empty)
    val dependencies = notebookConfig.dependencies.toList.flatMap(_.getOrElse("python", Nil)).distinct
    val pipRepos = notebookConfig.repositories.toList.flatten.collect { case x: pip => x }
    if (dependencies.nonEmpty) {
      for {
        dir         <- effectBlocking(Paths.get(sanitize(config.storage.cache), sanitize(notebook.path), "venv").toAbsolutePath)
        _           <- CurrentTask.update(_.progress(0.0, Some("Initializing virtual environment")))
        txtDeps     <- flattenDeps(dependencies)
        pyConfig     = PythonDepConfig(txtDeps, pipRepos, notebookConfig.exclusions.toList.flatten)
        initialized <- initVenv(dir, pyConfig)
        _           <- ZIO.when(initialized)(CurrentTask.update(_.progress(0.2, Some("Installing dependencies"))))
        _           <- ZIO.when(initialized)(installDependencies(dir, pyConfig))
      } yield Some(dir)
    } else ZIO.none
  }

  private val depFileName = ".polynote-python-deps.yml"

  // we could break this out into utils or something...
  class StringLogger extends ProcessLogger {

    private val writer = new StringWriter()
    private val printer = new PrintWriter(writer)

    override def out(s: => String): Unit = {
      stdout.println(s)
      printer.println(s)
    }
    override def err(s: => String): Unit = {
      stderr.println(s)
      printer.println(s)
    }
    override def buffer[T](f: => T): T = f

    override def toString: String = writer.toString
  }

  private def runCommand(cmd: Seq[String]) = for {
    _          <- Logging.info(s"Running command: ${cmd.mkString(" ")}")
    (ret, log) <- effectBlocking {
      val logger = new StringLogger
      val ret = cmd.!(logger)
      (ret, logger.toString)
    }
    _          <- ZIO.when(ret != 0)(ZIO.fail(new Exception(log)))
  } yield ()

  private def deleteDir(path: Path): ZIO[Blocking, Throwable, Unit] = {
    effectBlocking(Files.isDirectory(path)).flatMap {
      case true =>
        for {
          files <- effectBlocking(Files.list(path).iterator().asScala.toList)
          _     <- ZIO.foreach_(files)(deleteDir)
          _     <- effectBlocking(Files.delete(path))
        } yield ()
      case false =>
        effectBlocking(Files.delete(path))
    }
  }

  /**
    * Initialize virtual environment if it doesn't exist. If it already exists, check to see whether the configuration
    * has changed (e.g., user added/removed a dependency); if so, clear out the venv.
    * @param path     directory to create the venv
    * @param depConf  current dependency config
    * @return         whether the venv was created
    */
  private def initVenv(path: Path, depConf: PythonDepConfig) = {
    val writeConfig = effectBlocking {
      val configStr = depConf.asJson.asYaml.spaces2
      Files.write(path.resolve(depFileName), configStr.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW) // write file, failing if file already exists
    }
    val init = for {
      _ <- CurrentTask.update(_.progress(0.1, Some("Creating new virtual environment")))
      _ <- runCommand(Seq("virtualenv", "--system-site-packages", "--python=python3", path.toString))
      _ <- writeConfig
    } yield true

    effectBlocking(path.toFile.exists()).flatMap {
      case true  =>
        // venv already exists. We need to check whether to bust the cache, either because the user said so explicitly or by
        // parsing the config (if it exists) and comparing it to the current one.
        val bustCache = depConf.dependencies.exists(_.endsWith(noCacheSentinel))

        val clearThenInit =
          CurrentTask.update(_.progress(0.1, Some("Clearing outdated virtual environment"))) *>
            deleteDir(path) *>
            init

        if (bustCache) {
          clearThenInit
        } else {
          val configFile = path.resolve(depFileName).toFile

          effectBlocking(configFile.exists()).flatMap {
            case true =>
              val parseConfig = effectBlocking(new FileReader(configFile)).bracketAuto {
                reader =>
                  ZIO.fromEither {
                    yaml.Parser.default.parse(reader).flatMap(_.as[PythonDepConfig])
                  }
              }

              for {
                config      <- parseConfig
                initialized <- if (!config.equals(depConf)) clearThenInit else ZIO.succeed(false)
              } yield initialized
            case false =>
              for {
                _ <- CurrentTask.update(_.progress(0.1, Some("Initializing virtual environment")))
                _ <- writeConfig
              } yield false
        }

        }
      case false => init
    }
  }

  private def installDependencies(
    venv: Path,
    depConf: PythonDepConfig
  ) = {

    val options: List[String] = depConf.repositories.collect {
      case pip(url) => Seq("--extra-index-url", url)
    }.flatten

    def pip(action: String, dep: String, extraOptions: List[String] = Nil) = {
      val baseCmd = List(s"$venv/bin/pip", action)
      val cmd = baseCmd ::: options ::: extraOptions ::: dep :: Nil
      runCommand(cmd)
    }

    val dependencies = depConf.dependencies.map(_.stripSuffix(noCacheSentinel))
    val depProgress = 0.5 / dependencies.size
    // Breakdown of progress updates per dependency. Multipliers should add up to 1
    val depInitProgress = depProgress * 0.2
    val depInstalledProgress = depProgress * 0.7
    val depDownloadedProgress = depProgress * 0.1

    for {
      taskManager       <- TaskManager.access
      installSemaphore  <- Semaphore.make(1)
      parentTask        <- CurrentTask.access
      _                 <- ZIO.foreachPar_(dependencies) {
        dep =>
          taskManager.runSubtask(s"Installing $dep") {
            // use semaphore to ensure only one `pip install` is happening at a time.
            val doInstall = installSemaphore.withPermit(for {
              _ <- CurrentTask.update(_.progress(0.1))
              _ <- parentTask.update(task => ZIO.succeed(task.progress(task.progressFraction + depInitProgress, Some(s"Installing $dep"))))
              _ <- pip("install", dep)
            } yield ())

            for {
              _ <- doInstall
              _ <- CurrentTask.update(_.progress(0.8).copy(label = s"Downloading $dep"))
              _ <- parentTask.update(task => ZIO.succeed(task.progress(task.progressFraction + depInstalledProgress)))
              _ <- pip("download", dep, List("--dest", s"$venv/deps/"))
              _ <- CurrentTask.update(_.progress(0.9))
              _ <- parentTask.update(task => ZIO.succeed(task.progress(task.progressFraction + depDownloadedProgress)))
            } yield ()
          }
      }
      _ <- parentTask.update(task => ZIO.succeed(task.progress(0.9, Some("finishing..."))))
    } yield ()
  }

}
