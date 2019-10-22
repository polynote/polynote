package polynote.kernel.interpreter.python

import java.util.concurrent.atomic.AtomicReference

import org.apache.spark.sql.SparkSession
import polynote.kernel.{BaseEnv, GlobalEnv, ScalaCompiler, TaskManager}
import polynote.kernel.environment.{Config, CurrentNotebook, CurrentTask}
import polynote.kernel.interpreter.Interpreter
import py4j.GatewayServer
import zio.{Task, RIO, ZIO}
import zio.blocking.{Blocking, effectBlocking}

object PySparkInterpreter {

  object Factory extends Interpreter.Factory {
    def languageName: String = "Python"
    def apply(): RIO[Blocking with Config with ScalaCompiler.Provider with CurrentNotebook with CurrentTask with TaskManager, Interpreter] = for {
      venv        <- VirtualEnvFetcher.fetch()
      gatewayRef   = new AtomicReference[GatewayServer]()
      interpreter <- PythonInterpreter(venv, "py4j" :: "pyspark" :: PythonInterpreter.sharedModules, getPy4JError(gatewayRef))
      _           <- setupPySpark(interpreter, gatewayRef)
    } yield interpreter

    override val requireSpark: Boolean = true
    override val priority: Int = 1
  }

  private def getPy4JError(gatewayRef: AtomicReference[GatewayServer]): String => Option[Throwable] = {
    id =>
      val obj = for {
        gatewayServer <- Option(gatewayRef.get())
        gateway       <- Option(gatewayServer.getGateway)
        obj           <- Option(gateway.getObject(id))
      } yield obj

      obj.collect {
        case err: Throwable => err
      }
  }

  private def setupPySpark(interp: PythonInterpreter, gatewayRef: AtomicReference[GatewayServer]): RIO[Blocking with TaskManager, Unit] =
    TaskManager.run("PySpark", "Initializing PySpark") {
      for {
        spark   <- ZIO(SparkSession.builder().getOrCreate())
        _       <- CurrentTask.update(_.progress(0.2))
        _       <- pySparkImports(interp, spark)
        _       <- CurrentTask.update(_.progress(0.3))
        gateway <- startPySparkGateway(spark)
        _       <- CurrentTask.update(_.progress(0.7))
        _       <- ZIO(gatewayRef.set(gateway))
        _       <- registerGateway(interp, gateway)
        _       <- CurrentTask.update(_.progress(0.9))
      } yield ()
    }

  private def pySparkImports(interp: PythonInterpreter, spark: SparkSession): Task[Unit] = {
    // if we are running in local mode we need to set this so the executors can find the venv's python
    interp.jep {
      jep =>
        jep.eval("import os")
        if (spark.sparkContext.master.contains("local")) {
          jep.eval("""os.environ["PYSPARK_PYTHON"] = os.environ.get("PYSPARK_DRIVER_PYTHON", "python3")""")
        } else {
          jep.eval("""os.environ["PYSPARK_PYTHON"] = "python3" """)
        }
        jep.exec(
          """from py4j.java_gateway import java_import, JavaGateway, JavaObject, GatewayParameters, CallbackServerParameters
            |from pyspark.conf import SparkConf
            |from pyspark.context import SparkContext
            |from pyspark.sql import SparkSession, SQLContext
            |""".stripMargin)
    }
  }

  private def startPySparkGateway(spark: SparkSession) = effectBlocking {
    val gateway = new GatewayServer(
      spark,
      0,
      0,
      GatewayServer.DEFAULT_CONNECT_TIMEOUT,
      GatewayServer.DEFAULT_READ_TIMEOUT,
      null)

    gateway.start(true)

    while (gateway.getListeningPort == -1) {
      Thread.sleep(20)
    }

    gateway
  }

  private def registerGateway(interpreter: PythonInterpreter, gateway: GatewayServer) = interpreter.jep {
    jep =>
      val javaPort = gateway.getListeningPort

      jep.eval(
        s"""gateway = JavaGateway(
           |  auto_field = True,
           |  auto_convert = True,
           |  gateway_parameters = GatewayParameters(port = $javaPort, auto_convert = True),
           |  callback_server_parameters = CallbackServerParameters(port = 0))""".stripMargin)

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

}
