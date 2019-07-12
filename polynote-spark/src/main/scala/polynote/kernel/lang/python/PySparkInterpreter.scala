package polynote.kernel.lang.python

import cats.effect.IO
import cats.implicits._
import org.apache.spark.sql.SparkSession
import polynote.kernel.dependency.DependencyProvider
import polynote.kernel.lang.LanguageInterpreter
import polynote.kernel.util.KernelContext
import py4j.GatewayServer

class PySparkInterpreter(ctx: KernelContext, dependencyProvider: DependencyProvider) extends PythonInterpreter(ctx, dependencyProvider) {

  override def sharedModules: List[String] = "pyspark" :: super.sharedModules

  val postInit: IO[Unit] = IO.fromEither(dependencyProvider.as[PySparkVirtualEnvDependencyProvider]).map {
    p =>
      withJep {
        jep.eval(p.afterInit)
      }
  }

  override def init(): IO[Unit] = super.init() >> withJep {
    try {

      // initialize py4j and pyspark in the way they expect

      val spark = SparkSession.builder().getOrCreate()
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
    } catch {
      case err: Throwable => logger.error(err)("Failed to initialize PySpark")
    }
  } >> postInit

}

object PySparkInterpreter {
  class Factory extends LanguageInterpreter.Factory[IO] {
    override def languageName: String = "Python"
    override def apply(kernelContext: KernelContext, dependencies: DependencyProvider): LanguageInterpreter[IO] =
      new PySparkInterpreter(kernelContext, dependencies)
  }

  def factory(): Factory = new Factory
}
