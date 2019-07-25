package polynote.kernel.lang.python

import cats.effect.IO
import cats.implicits._
import org.apache.spark.sql.SparkSession
import polynote.kernel.RuntimeError.RecoveredException
import polynote.kernel.dependency.{DependencyManagerFactory, DependencyProvider}
import polynote.kernel.util.KernelContext
import polynote.kernel.{Result, RuntimeError}
import py4j.GatewayServer

class PySparkInterpreter(ctx: KernelContext, dependencyProvider: DependencyProvider) extends PythonInterpreter(ctx, dependencyProvider) {

  override def sharedModules: List[String] = "pyspark" :: super.sharedModules

  private var gatewayRef: GatewayServer = _

  override def setup(): IO[Unit] = super.setup() >> withJep {
    try {

      // initialize py4j and pyspark in the way they expect

      val spark = SparkSession.builder().getOrCreate()

      // if we are running in local mode we need to set this so the executors can find the venv's python
      if (spark.sparkContext.master.contains("local")) {
        jep.eval("""os.environ["PYSPARK_PYTHON"] = os.environ["PYSPARK_DRIVER_PYTHON"]""")
      } else {
        jep.eval("""os.environ["PYSPARK_PYTHON"] = "python3" """)
      }
      jep.eval("from py4j.java_gateway import java_import, JavaGateway, JavaObject, GatewayParameters, CallbackServerParameters")
      jep.eval("from pyspark.conf import SparkConf")
      jep.eval("from pyspark.context import SparkContext")
      jep.eval("from pyspark.sql import SparkSession, SQLContext")
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

      val javaPort = gateway.getListeningPort
      gateway.getCallbackClient.getPort

      jep.eval(
        s"""gateway = JavaGateway(
          |  auto_field = True,
          |  auto_convert = True,
          |  gateway_parameters = GatewayParameters(port = $javaPort, auto_convert = True),
          |  callback_server_parameters = CallbackServerParameters(port = 0))""".stripMargin)

      val pythonPort = jep.getValue("gateway.get_callback_server().get_listening_port()", classOf[java.lang.Number]).intValue()

      gateway.resetCallbackClient(py4j.GatewayServer.defaultAddress(), pythonPort)

      jep.eval("java_import(gateway.jvm, \"org.apache.spark.SparkEnv\")")
      jep.eval("java_import(gateway.jvm, \"org.apache.spark.SparkConf\")")
      jep.eval("java_import(gateway.jvm, \"org.apache.spark.api.java.*\")")
      jep.eval("java_import(gateway.jvm, \"org.apache.spark.api.python.*\")")
      jep.eval("java_import(gateway.jvm, \"org.apache.spark.mllib.api.python.*\")")
      jep.eval("java_import(gateway.jvm, \"org.apache.spark.sql.*\")")
      jep.eval("java_import(gateway.jvm, \"org.apache.spark.sql.hive.*\")")
      jep.eval("java_import(gateway.jvm, \"scala.Tuple2\")")
      jep.eval("__sparkConf = SparkConf(_jvm = gateway.jvm, _jconf = gateway.entry_point.sparkContext().getConf())")
      jep.eval("sc = SparkContext(jsc = gateway.jvm.org.apache.spark.api.java.JavaSparkContext(gateway.entry_point.sparkContext()), gateway = gateway, conf = __sparkConf)")
      jep.eval("spark = SparkSession(sc, gateway.entry_point)")
      jep.eval("sqlContext = spark._wrapped")
      jep.eval("from pyspark.sql import DataFrame")
      gatewayRef = gateway
    } catch {
      case err: Throwable => logger.error(err)("Failed to initialize PySpark")
    }
  }

  override def getPyErrorInfo(cellName: String, cellContents: String)(code: String): Either[Throwable, Unit] = {
    jep.eval("__py4j_err__ = None")
    jep.eval("__raise_err__ = None")
    kernelContext.runInterruptible {
      jep.eval(
        s"""
           |try:
           |    $code
           |except Exception as e:
           |    if sys.exc_info()[0].__name__ == 'Py4JJavaError':
           |        __py4j_err__ = e
           |    else:
           |        __raise_err__ = e
         """.stripMargin.trim)
    }
    val res =
      Option(jep.getValue("__py4j_err__")).map {
        _ =>
          val py4jObjectId = jep.getValue("__py4j_err__.java_exception._target_id", classOf[String])

          // get the Python part of the error. We can do the `left.get` bit since we are raising an error so we know it'll be a Left
          val pyErr = super.getPyErrorInfo(cellName, cellContents)("raise __py4j_err__").left.get

          Option(gatewayRef).map(_.getGateway.getObject(py4jObjectId) match {
            case t: Throwable =>
              val err = new RuntimeException(pyErr.getMessage, t)
              err.setStackTrace(pyErr.getStackTrace)
              Left(err)
            case _ => Right(())
          }).getOrElse(super.getPyErrorInfo(cellName, cellContents)("raise __py4j_err__"))  // this should probably never happen...
      }.orElse {
        Option(jep.getValue("__raise_err__")).map( _ =>
          super.getPyErrorInfo(cellName, cellContents)("raise __raise_err__")
        )
      }.getOrElse(Right(()))

    jep.eval(s"del __py4j_err__")
    jep.eval(s"del __raise_err__")
    res
  }

}

object PySparkInterpreter {
  class Factory extends PythonInterpreter.Factory {
    override def depManagerFactory: DependencyManagerFactory[IO] = PySparkVirtualEnvManager.Factory
    override def apply(kernelContext: KernelContext, dependencies: DependencyProvider): PythonInterpreter =
      new PySparkInterpreter(kernelContext, dependencies)
  }

  def factory(): Factory = new Factory
}
