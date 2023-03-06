package polynote.kernel.interpreter.python

import java.net.InetAddress
import java.nio.file.{FileSystems, Files, Path}
import java.util.concurrent.atomic.AtomicReference
import jep.Jep
import jep.python.{PyCallable, PyObject}
import org.apache.commons.lang3.RandomStringUtils
import org.apache.spark.sql.{DataFrame, Dataset, Row, SparkSession}
import polynote.config.PySparkConfig
import polynote.kernel.{BaseEnv, GlobalEnv, InterpreterEnv, ScalaCompiler}
import polynote.kernel.environment.{Config, CurrentNotebook, CurrentRuntime, CurrentTask}
import polynote.kernel.interpreter.{Interpreter, State}
import polynote.kernel.task.TaskManager
import polynote.kernel.util._
import py4j.GatewayServer
import py4j.GatewayServer.GatewayServerBuilder
import zio.{RIO, Runtime, Task, ZIO}
import zio.blocking.{Blocking, effectBlocking}
import zio.internal.Executor

import scala.util.{Properties, Try}
import scala.collection.JavaConverters._

class PySparkInterpreter(
  _compiler: ScalaCompiler,
  jepInstance: Jep,
  jepExecutor: Executor,
  jepThread: AtomicReference[Thread],
  jepBlockingService: Blocking,
  runtime: Runtime[Any],
  pyApi: PythonInterpreter.PythonAPI,
  venvPath: Option[Path],
  pySparkConfig: Option[PySparkConfig]
) extends PythonInterpreter(_compiler, jepInstance, jepExecutor, jepThread, jepBlockingService, runtime, pyApi, venvPath) {

  val gatewayRef = new AtomicReference[GatewayServer]()

  private val dataFrameType = compiler.global.ask(() => compiler.global.typeOf[Dataset[Row]])

  override protected def injectGlobals(globals: PyObject): RIO[CurrentRuntime, Unit] = super.injectGlobals(globals) *> jep {
      jep =>
        val setItem = globals.getAttr("__setitem__", classOf[PyCallable])

        val pySparkSession = jep.getValue("spark", classOf[PyObject])
        setItem.call("spark", pySparkSession)

  }

  override def init(state: State): RIO[InterpreterEnv, State] =  for {
    spark   <- ZIO(SparkSession.builder().getOrCreate())
    _       <- exec(pysparkImports)
    doAuth  <- shouldAuthenticate
    gateway <- startPySparkGateway(spark, doAuth)
    _       <- ZIO(gatewayRef.set(gateway))
    _       <- registerGateway(gateway, doAuth)
    res <- super.init(state)
  } yield res

  /**
    * Handle setting up PySpark.
    *
    * First, we need to pick the python interpreter. Unfortunately this means we need to re-implement Spark's interpreter
    * configuration logic, because that's only implemented inside SparkSubmit (and only when you use `pyspark-shell` actually).
    *
    * Here's the order we follow for the driver python executable (from [[org.apache.spark.launcher.SparkSubmitCommandBuilder]]):
    *    1. conf spark.pyspark.driver.python
    *    2. conf spark.pyspark.python
    *    3. environment variable PYSPARK_DRIVER_PYTHON
    *    4. environment variable PYSPARK_PYTHON
    *
    * For the executors we just omit the driver python - so it's just:
    *    1. conf spark.pyspark.python
    *    2. environment variable PYSPARK_PYTHON
    *
    * Additionally, to load pyspark itself we try to grab the its location from the Spark distribution.
    * This ensures that all the versions match up.
    *
    * WARNING: Using pyspark from `pip install pyspark`, could break things - don't use it!
    */
  protected def pysparkImports: String = {
    val sparkConf =  org.apache.spark.repl.Main.conf
    val defaultPython = "python3"
    val pythonDriverExecutable = sparkConf.get("spark.pyspark.driver.python",
      sparkConf.get("spark.pyspark.python",
        envOrProp("PYSPARK_DRIVER_PYTHON",
          envOrProp("PYSPARK_PYTHON", defaultPython))))

    val pythonExecutable = sparkConf.get("spark.pyspark.python",
      envOrProp("PYSPARK_PYTHON", defaultPython))

    s"""
       |import os
       |import sys
       |
       |os.environ["PYSPARK_PYTHON"] = "$pythonExecutable"
       |os.environ["PYSPARK_DRIVER_PYTHON"] = "$pythonDriverExecutable"
       |
       |# grab the pyspark included in the spark distribution, if available.
       |spark_home = os.environ.get("SPARK_HOME")
       |if spark_home:
       |    spark_python = os.path.join(spark_home, "python")
       |    sys.path.insert(1, spark_python)
       |    import glob
       |    py4j_dir = os.path.join(spark_home, 'python', 'lib')
       |    try:
       |        py4j_path = glob.glob(os.path.join(py4j_dir, 'py4j-*.zip'))[0]  # we want to use the py4j distributed with pyspark
       |        sys.path.insert(1, py4j_path)
       |    except IndexError as err:
       |        raise IndexError('No py4j found using SPARK_HOME: ' + spark_home + ' searched in ' + py4j_dir)
       |
       |    pythonpath = os.pathsep.join(filter(lambda x: x is not None, [spark_python, py4j_path, os.environ.get("PYTHONPATH", None)]))
       |    os.environ["PYTHONPATH"] = pythonpath
       |    # TODO: we need to set spark.executorEnv.PYTHONPATH somehow (before the session is created)!
       |
       |from py4j.java_gateway import java_import, JavaGateway, JavaObject, GatewayParameters, CallbackServerParameters
       |from pyspark.conf import SparkConf
       |from pyspark.context import SparkContext
       |from pyspark.sql import SparkSession, SQLContext
       |""".stripMargin
  }

  /**
    * Whether or not to authenticate, based on the py4j version available.
    * We can get rid of this when we drop support for Spark 2.1
    */
  private def shouldAuthenticate = jep {
    jep =>

      jep.eval("import py4j")
      jep.eval("import pkg_resources")
      jep.getValue(
        "pkg_resources.parse_version(py4j.__version__) >= pkg_resources.parse_version('0.10.7')",
        classOf[java.lang.Boolean]
      ).booleanValue
  }

  private lazy val py4jToken: String = RandomStringUtils.randomAlphanumeric(256)

  private lazy val gwBuilder: GatewayServerBuilder = new GatewayServerBuilder()
    .javaPort(0)
    .callbackClient(0, InetAddress.getByName(GatewayServer.DEFAULT_ADDRESS))
    .connectTimeout(GatewayServer.DEFAULT_CONNECT_TIMEOUT)
    .readTimeout(GatewayServer.DEFAULT_READ_TIMEOUT)
    .customCommands(null)

  /**
    * Start the py4j gateway, ensuring it's run on the Jep thread so it sees the proper classloader.
    */
  private def startPySparkGateway(spark: SparkSession, doAuth: Boolean) = jep {
    _ =>
    val builder = if (doAuth) {
      // use try here just to be extra careful
      try gwBuilder.authToken(py4jToken) catch {
        case err: Throwable => gwBuilder
      }
    } else gwBuilder

    val gateway = builder.entryPoint(spark).build()

    gateway.start(true)

    while (gateway.getListeningPort == -1) {
      Thread.sleep(20)
    }

    gateway
  }

  private def registerGateway(gateway: GatewayServer, doAuth: Boolean) = jep {
    jep =>
      val javaPort = gateway.getListeningPort

      if (doAuth) {
        jep.eval(
          s"""gateway = JavaGateway(
             |  auto_field = True,
             |  auto_convert = True,
             |  gateway_parameters = GatewayParameters(port = $javaPort, auto_convert = True, auth_token = "$py4jToken"),
             |  callback_server_parameters = CallbackServerParameters(port = 0, auth_token = "$py4jToken"))""".stripMargin)
      } else {
        jep.eval(
          s"""gateway = JavaGateway(
             |  auto_field = True,
             |  auto_convert = True,
             |  gateway_parameters = GatewayParameters(port = $javaPort, auto_convert = True),
             |  callback_server_parameters = CallbackServerParameters(port = 0))""".stripMargin)
      }

      // Register shutdown handlers so pyspark exits cleanly. We need to make sure that all threads are closed before stopping jep.
      jep.eval("import atexit")
      jep.eval(
        """def __exit_pyspark__():
          |    # remove the link between pyspark's sc and the real sc, so the call to stop() doesn't reach back into the real sc
          |    sc._jsc = None
          |    # stop pyspark and close all its threads (accumulator server etc)
          |    sc.stop()
          |    # for local mode to work properly, we need to clean up some of this global state so we can start another pyspark instance later
          |    SparkContext._gateway = None
          |    SparkContext._jvm = None
          |    SparkContext._next_accum_id = 0
          |    SparkContext._active_spark_context = None
          |    SparkContext._python_includes = None
          |    # shutdown the py4j gateway in order to close all _its_ threads as well
          |    gateway.shutdown()
          |""".stripMargin)
      jep.eval("atexit.register(__exit_pyspark__)")

      val pythonPort = jep.getValue("gateway.get_callback_server().get_listening_port()", classOf[java.lang.Number]).intValue()

      gateway.resetCallbackClient(py4j.GatewayServer.defaultAddress(), pythonPort)

      jep.exec(
        s"""java_import(gateway.jvm, "org.apache.spark.SparkEnv")
           |java_import(gateway.jvm, "org.apache.spark.SparkConf")
           |java_import(gateway.jvm, "org.apache.spark.api.java.*")
           |java_import(gateway.jvm, "org.apache.spark.api.python.*")
           |java_import(gateway.jvm, "org.apache.spark.mllib.api.python.*")
           |java_import(gateway.jvm, "org.apache.spark.sql.*")
           |java_import(gateway.jvm, "org.apache.spark.sql.hive.*")
           |
           |__sparkConf = SparkConf(_jvm = gateway.jvm, _jconf = gateway.entry_point.sparkContext().getConf())
           |sc = SparkContext(jsc = gateway.jvm.org.apache.spark.api.java.JavaSparkContext(gateway.entry_point.sparkContext()), gateway = gateway, conf = __sparkConf)
           |spark = SparkSession(sc, gateway.entry_point)
           |sqlContext = SQLContext._get_or_create(sc)
           |from pyspark.sql import DataFrame
           |
           |
           |""".stripMargin)

      // Archive venv dependencies and send it to Spark. We also add pyspark and py4j just in case.
      val pysparkModulesList = PySparkInterpreter.pysparkModules.fold(List.empty[String])(_.map(_.toString))
      jep.set("spark_py_libs", pysparkModulesList.toArray)
      jep.set("do_distribute_deps", pySparkConfig.flatMap(_.distributeDependencies).getOrElse(true))

      // These dependencies can't be distributed with pyspark. They need to be available on the executors.
      // See https://github.com/boto/boto3/issues/1770 for more details.
      val defaultExcludes = Array("boto", "h5py")
      jep.set("dist_excludes", pySparkConfig.map(_.distributionExcludes.toArray).getOrElse(defaultExcludes))

      jep.exec(
        s"""
           |from pathlib import Path
           |import shutil
           |
           |venv_path = "${venvPath.getOrElse("")}"
           |dependencies = []
           |if do_distribute_deps and venv_path:
           |    dependencies += list(Path(venv_path, 'deps').glob('*.whl'))
           |
           |# Add pyspark and py4j modules too.
           |if spark_py_libs:
           |    dependencies += [Path(x) for x in spark_py_libs]
           |
           |# Remove any exclusions
           |dependencies = [d for d in dependencies if not any(x in str(d) for x in dist_excludes)]
           |
           |# Unfortunately pyspark's `sc.addPyFile` modifies the sys.path, but we don't want that to happen in this case.
           |# so, we'll just add the files ourselves.
           |def addPyFile(path):
           |    sc.addFile(path)
           |    filename = os.path.basename(path)
           |    sc._python_includes.append(filename)
           |
           |for dep in dependencies:
           |    # we need to rename the wheels to zips because that's what spark wants... sigh
           |    as_zip = dep.with_suffix('.zip')
           |    if not as_zip.exists():
           |        shutil.copy(dep, as_zip)
           |    addPyFile(str(as_zip))
           |""".stripMargin)
  }

  override protected def convertToPython(jep: Jep): PartialFunction[(String, Any), AnyRef] = super.convertToPython(jep) orElse {
    case (name, dataFrame: Dataset[_]) =>
        // should this go through py4j instead? We could use gateway.getGateway.putObject, but how to retrieve it on python side?
        // will just pass directly to the DataFrame constructor
      val pyDf = gatewayRef.get().getGateway.putObject(name, dataFrame)
      jep.getValue("DataFrame", classOf[PyCallable]).callAs(classOf[PyObject], jep.getValue("gateway.java_gateway_server.getGateway().getObject", classOf[PyCallable]).callAs(classOf[PyObject], name), jep.getValue("sqlContext", classOf[PyObject]))
  }

  override protected def convertFromPython(jep: Jep): PartialFunction[(String, PyObject), (compiler.global.Type, Any)] = super.convertFromPython(jep) orElse {
    case ("DataFrame", obj) if jep.getValue("hasattr", classOf[PyCallable]).callAs(classOf[java.lang.Boolean], obj, "_jdf").booleanValue() =>
      // it's a Spark DataFrame
      jep.getValue("gateway.java_gateway_server.getGateway().putObject", classOf[PyCallable]).call("_jdf", obj.getAttr("_jdf", classOf[PyObject]))
      val df = gatewayRef.get().getGateway().getObject("_jdf").asInstanceOf[Dataset[Row]]
      (dataFrameType, df)
  }

  override protected def errorCause(get: PyCallable): Option[Throwable] = {
    val err = get.callAs(classOf[PyObject], "err")
    val errCls = get.callAs(classOf[String], "class")
    if (errCls == "Py4JJavaError") {
      for {
        javaExc       <- Option(err.getAttr("java_exception", classOf[PyObject]))
        py4jObjectId  <- Option(javaExc.getAttr("_target_id", classOf[String]))
        gatewayServer <- Option(gatewayRef.get())
        gateway       <- Option(gatewayServer.getGateway)
        exc           <- Option(gateway.getObject(py4jObjectId)).collect {
          case e: Throwable => e
        }
      } yield exc
    } else super.errorCause(get)
  }
}

object PySparkInterpreter {

  def apply(): RIO[BaseEnv with GlobalEnv with ScalaCompiler.Provider with CurrentNotebook with CurrentTask with TaskManager, PySparkInterpreter] = {
    for {
      venv    <- VirtualEnvFetcher.fetch()
      interp  <- PySparkInterpreter(venv)
    } yield interp
  }

  def apply(venv: Option[Path]): RIO[Config with ScalaCompiler.Provider, PySparkInterpreter] = {
    for {
      (compiler, jep, executor, jepThread, blocking, runtime, api) <- PythonInterpreter.interpreterDependencies(venv)
      config  <- Config.access
    } yield new PySparkInterpreter(compiler, jep, executor, jepThread, blocking, runtime, api, venv, config.spark.flatMap(_.pyspark))
  }

  // Grab the locations of the pyspark modules that are packaged with Spark: pyspark itself, and py4j.
  lazy val pysparkModules: Option[List[Path]] = for {
    spark_home <- sys.env.get("SPARK_HOME")
    path       <- Try(FileSystems.getDefault.getPath(spark_home, "python", "lib")).toOption
    files      <- Try(Files.newDirectoryStream(path, "*.zip").iterator().asScala.toList).toOption
  } yield files

  object Factory extends Interpreter.Factory {
    def languageName: String = "Python"
    def apply(): RIO[BaseEnv with GlobalEnv with ScalaCompiler.Provider with CurrentNotebook with CurrentTask with TaskManager, Interpreter] = PySparkInterpreter()

    override val requireSpark: Boolean = true
    override val priority: Int = 1

    // Add the pyspark modules that are packaged with Spark to the executor's PYTHONPATH. We ship these modules over to the
    // executors in [[PySparkInterpreter#registerGateway]] (alongside the other python libs), since the executors aren't
    // guaranteed to have pyspark installed on them.
    override def sparkConfig(config: Map[String, String]): RIO[BaseEnv with GlobalEnv, Map[String, String]] = {
      val cfg = pysparkModules.fold(config) {
        files =>
          val pythonPath = files.map(f => s"./${f.getFileName}").mkString(":")
          config ++ Map("spark.executorEnv.PYTHONPATH" -> pythonPath)
      }
      super.sparkConfig(cfg)
    }
  }

}
