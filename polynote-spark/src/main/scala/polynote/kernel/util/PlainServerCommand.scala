package polynote.kernel.util

import java.nio.file.{Path, Paths}

object PlainServerCommand {

  def apply(
    sparkConfig: Map[String, String],
    mainClass: String = "polynote.server.SparkServer",
    serverArgs: List[String] = Nil
  ): Seq[String] = {

    val allDriverOptions =
      sparkConfig.get("spark.driver.extraJavaOptions").toList :+ "-Dlog4j.configuration=log4j.properties"

    val javaCommand = sys.props.get("java.home").map { sysJava =>
      Paths.get(sysJava, "bin", "java").toString
    }.getOrElse("java")

    val cp = sys.props.get("java.class.path").toList.flatMap(Seq("-cp", _))

    Seq(javaCommand) ++ allDriverOptions ++ cp ++ Seq(mainClass) ++ serverArgs
  }
}
