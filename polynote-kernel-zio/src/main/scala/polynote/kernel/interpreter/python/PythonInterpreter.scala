package polynote.kernel.interpreter
package python
import java.nio.file.Paths
import java.util
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.concurrent.{Executors, LinkedBlockingQueue, ThreadFactory}

import jep.python.{PyCallable, PyObject}
import jep.{Jep, JepConfig, NamingConventionClassEnquirer, SubInterpreter}
import polynote.kernel.environment.{CurrentNotebook, CurrentRuntime}
import polynote.kernel.{CompileErrors, Completion, InterpreterEnv, KernelReport, Pos, ResultValue, ScalaCompiler, Signatures, TaskManager}
import polynote.messages.CellID
import polynote.runtime.python.{PythonFunction, PythonObject, TypedPythonObject}
import zio.internal.{ExecutionMetrics, Executor}
import zio.blocking.{Blocking, effectBlocking}
import zio.{Runtime, Task, TaskR, ZIO}

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext
import scala.reflect.{ClassTag, classTag}

class PythonInterpreter private (
  compiler: ScalaCompiler,
  jepInstance: Jep,
  jepExecutor: Executor,
  jepThread: AtomicReference[Thread],
  jepBlockingService: Blocking,
  runtime: Runtime[Any],
  pyApi: PythonInterpreter.PythonAPI
) extends Interpreter {
  import pyApi._

  private val runner: PythonObject.Runner = new PythonObject.Runner {
    def run[T](task: => T): T = if (Thread.currentThread() eq jepThread.get()) {
      task
    } else {
      runtime.unsafeRun(effectBlocking(task).lock(jepExecutor).provide(jepBlockingService))
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

  def run(code: String, state: State): TaskR[InterpreterEnv, State] = for {
    parsed    <- parse(code, s"Cell${state.id}")
    compiled  <- compile(parsed)
    locals    <- eval[PyObject]("{}")
    globals   <- populateGlobals(state)
    _         <- injectGlobals(globals)
    resState  <- run(compiled, globals, locals, state)
  } yield resState

  def completionsAt(code: String, pos: Int, state: State): Task[List[Completion]] = ???

  def parametersAt(code: String, pos: Int, state: State): Task[Option[Signatures]] = ???

  def init(state: State): TaskR[InterpreterEnv, State] = for {
    _ <- exec(setup)
  } yield state

  def shutdown(): Task[Unit] = jep(_.close())

  protected def jep[T](fn: Jep => T): Task[T] = effectBlocking(fn(jepInstance)).lock(jepExecutor).provide(jepBlockingService)
  protected def exec(code: String): Task[Unit] = jep(_.exec(code))
  protected def eval[T : ClassTag](code: String): Task[T] = jep(_.getValue(code, classTag[T].runtimeClass.asInstanceOf[Class[T]]))
  protected def setValue(name: String, value: AnyRef): Task[Unit] = jep(_.set(name, value))

  protected def setup: String =
    """import os, sys, ast, jedi, shutil
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
      |        return { 'globals': _globals, 'locals': _locals }
      |    except Exception as err:
      |        import traceback
      |        typ, err_val, tb = sys.exc_info()
      |        trace = ArrayList()
      |        for frame in traceback.extract_tb(tb):
      |            trace.add(StackTraceElement(frame.filename.split("/")[-1], frame.name, frame.filename, frame.lineno))
      |        return { 'stackTrace': trace, 'message': getattr(err_val, 'message', str(err_val)), 'class': typ.__name__  }
      |
      |
      |""".stripMargin

  protected def injectGlobals(globals: PyObject): TaskR[CurrentRuntime, Unit] = CurrentRuntime.access.flatMap {
    runtime =>
      jep {
        jep =>
          val setItem = globals.getAttr("__setitem__", classOf[PyCallable])
          setItem.call("kernel", runtime)
      }
  }

  protected def populateGlobals(state: State): Task[PyObject] = jep {
    jep =>
      val prevStates = state.takeWhile(!_.isInstanceOf[PythonState]).reverse
      val (globalsDict, rest) = prevStates match {
        case PythonState(_, _, _, globalsDict) :: rest => (globalsDict.getAttr("copy", classOf[PyCallable]).callAs(classOf[PyObject]), rest)
        case others => (jep.getValue("{}", classOf[PyObject]), others)
      }

      val addGlobal = globalsDict.getAttr("__setitem__", classOf[PyCallable])

      prevStates.map(_.values).map {
        values => values.map(v => v.name -> v.value).toMap
      }.foldLeft(Map.empty[String, Any])(_ ++ _).foreach {
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

  protected def run(compiled: PyObject, globals: PyObject, locals: PyObject, state: State): TaskR[CurrentRuntime, State] =
    CurrentRuntime.access.flatMap {
      kernelRuntime => jep {
        jep =>

          val run = jep.getValue("__polynote_run__", classOf[PyCallable])
          val result = run.callAs(classOf[PyObject], compiled, globals, locals, kernelRuntime)
          val get = result.getAttr("get", classOf[PyCallable])

          get.callAs(classOf[PyObject], "stackTrace") match {
            case null =>
              val globals = get.callAs(classOf[PyObject], "globals")
              val locals = get.callAs(classOf[PyObject], "locals")

              val localsItems = dictToItemsList(locals)
              val getLocal = localsItems.getAttr("__getitem__", classOf[PyCallable])
              val numLocals = len(localsItems)
              val resultValues = (0 until numLocals).map { i =>
                val item = getLocal.callAs(classOf[PyObject], Int.box(i))
                import compiler.global._
                if (item != null) {
                  val getField = item.getAttr("__getitem__", classOf[PyCallable])
                  val key = getField.callAs(classOf[String], Integer.valueOf(0))
                  val pyValue = getField.callAs(classOf[PyObject], Integer.valueOf(1))
                  val typeStr = typeName(pyValue)
                  if (typeStr != "NoneType" && typeStr != "module") {
                    val (typ, value) = typeStr match {
                      case "int" => (typeOf[Long], pyValue.as(classOf[java.lang.Number]).longValue())
                      case "float" => (typeOf[Double], pyValue.as(classOf[java.lang.Number]).doubleValue())
                      case "str" => (typeOf[String], pyValue.as(classOf[String]))
                      case "bool" => (typeOf[Boolean], pyValue.as(classOf[java.lang.Boolean]).booleanValue())
                      case "function" | "builtin_function_or_method" | "type" =>
                        (typeOf[PythonFunction], new PythonFunction(pyValue.as(classOf[PyCallable]), runner))
                      case "PyJObject" | "PyJCallable" | "PyJAutoCloseable" =>
                        val jValue = pyValue.as(classOf[Object])
                        val typ = runtime.unsafeRun(compiler.reflect(jValue)).symbol.info
                        (typ, jValue)
                      case other =>
                        val typ = appliedType(typeOf[TypedPythonObject[String]].typeConstructor, compiler.global.internal.constantType(Constant(other)))
                        (typ, new TypedPythonObject[String](pyValue, runner))
                    }
                    Some(new ResultValue(key, compiler.unsafeFormatType(typ.asInstanceOf[Type]), Nil, state.id, value, typ.asInstanceOf[Type], None))
                  } else None
                } else None
              }.toList.flatten
              PythonState(state.id, state.prev, resultValues.toList, globals)

            case trace =>
              val message = get.callAs(classOf[String], "message")
              val typ = get.callAs(classOf[String], "class")
              val els = trace.as(classOf[java.util.ArrayList[StackTraceElement]]).asScala.toArray
              val err = new RuntimeException(s"$typ: $message")
              err.setStackTrace(els)
              throw err
          }
      }
    }

  case class PythonState(id: CellID, prev: State, values: List[ResultValue], globalsDict: PyObject) extends State {
    override def withPrev(prev: State): State = copy(prev = prev)
    override def updateValues(fn: ResultValue => ResultValue): State = copy(values = values.map(fn))
  }
}

object PythonInterpreter {

  private class PythonAPI(jep: Jep) {
    private val typeFn: PyCallable = jep.getValue("type", classOf[PyCallable])
    private val lenFn: PyCallable = jep.getValue("len", classOf[PyCallable])
    private val listFn: PyCallable = jep.getValue("list", classOf[PyCallable])
    private val dictToItemsListFn: PyCallable = jep.getValue("lambda x: list(x.items())", classOf[PyCallable])

    def typeName(obj: PyObject): String = typeFn.callAs(classOf[PyObject], obj).getAttr("__name__", classOf[String])
    def len(obj: PyObject): Int = lenFn.callAs(classOf[java.lang.Number], obj).intValue()
    def list(obj: PyObject): PyObject = listFn.callAs(classOf[PyObject], obj)
    def dictToItemsList(obj: PyObject): PyObject = dictToItemsListFn.callAs(classOf[PyObject], obj)
  }


  private def jepExecutor(jepThread: AtomicReference[Thread]): Executor = Executor.fromExecutionContext(Int.MaxValue) {
    ExecutionContext.fromExecutorService {
      Executors.newSingleThreadExecutor {
        new ThreadFactory {
          def newThread(r: Runnable): Thread = {
            val thread = new Thread(r)
            thread.setName("Python interpreter thread")
            thread.setDaemon(true)
            if (!jepThread.compareAndSet(null, thread)) {
              throw new IllegalStateException("Python interpreter thread died; can't replace it with a new one")
            }
            thread
          }
        }
      }
    }
  }

  // TODO: pull this from configuration?
  private def sharedModules: List[String] = List("numpy", "google")

  private def mkJep: TaskR[ScalaCompiler.Provider, Jep] = ZIO.accessM[ScalaCompiler.Provider](_.scalaCompiler.classLoader).flatMap {
    classLoader => ZIO {
      val conf = new JepConfig()
        .addSharedModules(sharedModules: _*)
        .setInteractive(false)
        .setClassLoader(classLoader)
        .setClassEnquirer(new NamingConventionClassEnquirer(true).addTopLevelPackageName("polynote"))
      new SubInterpreter(conf)
    }
  }

  private def mkJepBlocking(jepExecutor: Executor) = new Blocking {
    val blocking: Blocking.Service[Any] = new Blocking.Service[Any] {
      def blockingExecutor: ZIO[Any, Nothing, Executor] = ZIO.succeed(jepExecutor)
    }
  }

  // TODO: need to reimplement venv stuff
  def apply(): TaskR[ScalaCompiler.Provider, PythonInterpreter] = {
    val jepThread = new AtomicReference[Thread](null)
    for {
      compiler <- ZIO.access[ScalaCompiler.Provider](_.scalaCompiler)
      executor <- ZIO.effectTotal(jepExecutor(jepThread))
      jep      <- mkJep.lock(executor)
      blocking  = mkJepBlocking(executor)
      api      <- effectBlocking(new PythonAPI(jep)).lock(executor).provide(blocking)
      runtime  <- ZIO.runtime[Any]
    } yield new PythonInterpreter(compiler, jep, executor, jepThread, blocking, runtime, api)
  }

}
