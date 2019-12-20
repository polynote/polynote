package polynote.kernel.interpreter.python

import java.net.InetAddress
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

import jep.Jep
import jep.python.{PyCallable, PyObject}
import org.apache.commons.lang3.RandomStringUtils
import org.apache.spark.sql.SparkSession
import polynote.kernel.{BaseEnv, GlobalEnv, InterpreterEnv, ScalaCompiler, TaskManager}
import polynote.kernel.environment.{Config, CurrentNotebook, CurrentRuntime, CurrentTask}
import polynote.kernel.interpreter.{Interpreter, State}
import py4j.GatewayServer
import py4j.GatewayServer.GatewayServerBuilder
import zio.{RIO, Runtime, Task, ZIO}
import zio.blocking.{Blocking, effectBlocking}
import zio.internal.Executor

class PySparkInterpreter(
  compiler: ScalaCompiler,
  jepInstance: Jep,
  jepExecutor: Executor,
  jepThread: AtomicReference[Thread],
  jepBlockingService: Blocking,
  runtime: Runtime[Any],
  pyApi: PythonInterpreter.PythonAPI,
  venvPath: Option[Path]
) extends PythonInterpreter(compiler, jepInstance, jepExecutor, jepThread, jepBlockingService, runtime, pyApi, venvPath) {

  val gatewayRef = new AtomicReference[GatewayServer]()

  override protected def injectGlobals(globals: PyObject): RIO[CurrentRuntime, Unit] = super.injectGlobals(globals) *> jep {
      jep =>
        val setItem = globals.getAttr("__setitem__", classOf[PyCallable])

        val pySparkSession = jep.getValue("spark", classOf[PyObject])
        setItem.call("spark", pySparkSession)

  }

  override def init(state: State): RIO[InterpreterEnv, State] =  for {
    spark   <- ZIO(SparkSession.builder().getOrCreate())
    _       <- exec(pysparkImports(spark.sparkContext.isLocal))
    doAuth  <- shouldAuthenticate
    gateway <- startPySparkGateway(spark, doAuth)
    _       <- ZIO(gatewayRef.set(gateway))
    _       <- registerGateway(gateway, doAuth)
    res <- super.init(state)
  } yield res

  protected def pysparkImports(sparkLocal: Boolean): String = {
    val setPySpark = if (sparkLocal) {
      """os.environ["PYSPARK_PYTHON"] = os.environ.get("PYSPARK_DRIVER_PYTHON", "python3")"""
    } else {
      """os.environ["PYSPARK_PYTHON"] = "python3" """
    }

    s"""
       |import os
       |import sys
       |if "PYSPARK_PYTHON" not in os.environ:
       |    $setPySpark
       |
       |# grab the pyspark included in the spark distribution, if available.
       |spark_home = os.environ.get("SPARK_HOME")
       |if spark_home:
       |    sys.path.insert(1, os.path.join(spark_home, "python"))
       |    import glob
       |    py4j_path = glob.glob(os.path.join(spark_home, 'python', 'lib', 'py4j-*.zip'))[0]  # we want to use the py4j distributed with pyspark
       |    sys.path.insert(1, py4j_path)
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
      val py4jVersion = jep.getValue("py4j.__version__", classOf[String])

      val Version = "(\\d+).(\\d+).(\\d+)".r

      py4jVersion match {
        case Version(_, _, patch) if patch.toInt >= 7 => true
        case _ => false
      }
  }

  private lazy val py4jToken: String = RandomStringUtils.randomAlphanumeric(256)

  private lazy val gwBuilder: GatewayServerBuilder = new GatewayServerBuilder()
    .javaPort(0)
    .callbackClient(0, InetAddress.getByName(GatewayServer.DEFAULT_ADDRESS))
    .connectTimeout(GatewayServer.DEFAULT_CONNECT_TIMEOUT)
    .readTimeout(GatewayServer.DEFAULT_READ_TIMEOUT)
    .customCommands(null)

  private def startPySparkGateway(spark: SparkSession, doAuth: Boolean) = effectBlocking {
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
        """java_import(gateway.jvm, "org.apache.spark.SparkEnv")
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
          |sqlContext = spark._wrapped
          |from pyspark.sql import DataFrame
          |""".stripMargin)
  }

  override protected def errorCause(get: PyCallable): Option[Throwable] = {
    Option(get.callAs(classOf[String], "py4j_error")).flatMap {
      py4jObjectId =>
        val obj = for {
          gatewayServer <- Option(gatewayRef.get())
          gateway       <- Option(gatewayServer.getGateway)
          obj           <- Option(gateway.getObject(py4jObjectId))
        } yield obj

        obj.collect {
          case err: Throwable => err
        }
    }
  }
}

object PySparkInterpreter {

  def apply(venv: Option[Path]): RIO[ScalaCompiler.Provider, PySparkInterpreter] = {
    for {
      (compiler, jep, executor, jepThread, blocking, runtime, api) <- PythonInterpreter.interpreterDependencies(venv)
    } yield new PySparkInterpreter(compiler, jep, executor, jepThread, blocking, runtime, api, venv)
  }

  object Factory extends Interpreter.Factory {
    def languageName: String = "Python"
    def apply(): RIO[Blocking with Config with ScalaCompiler.Provider with CurrentNotebook with CurrentTask with TaskManager, Interpreter] = for {
      venv        <- VirtualEnvFetcher.fetch()
      interpreter <- PySparkInterpreter(venv)
    } yield interpreter

    override val requireSpark: Boolean = true
    override val priority: Int = 1
  }

}
