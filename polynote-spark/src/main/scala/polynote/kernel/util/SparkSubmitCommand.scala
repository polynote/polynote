package polynote.kernel.util

object SparkSubmitCommand {
  def parseQuotedArgs(str: String): List[String] = str.split('"').toList.sliding(2,2).toList.flatMap {
    case nonQuoted :: quoted :: Nil => nonQuoted.split("\\s+").toList ::: quoted :: Nil
    case nonQuoted :: Nil => nonQuoted.split("\\s+").toList
    case _ => sys.error("impossible sliding state")
  }.map(_.trim).filterNot(_.isEmpty)

  def apply(
    sparkConfig: Map[String, String],
    mainClass: String = "polynote.server.SparkServer",
    jarLocation: String = getClass.getProtectionDomain.getCodeSource.getLocation.getPath,
    serverArgs: List[String] = Nil
  ): Seq[String] = {

    val sparkArgs = (sparkConfig - "sparkSubmitArgs" - "spark.driver.extraJavaOptions" - "spark.submit.deployMode" - "spark.driver.memory")
      .flatMap(kv => Seq("--conf", s"${kv._1}=${kv._2}"))

    val sparkSubmitArgs = sparkConfig.get("sparkSubmitArgs").toList.flatMap(parseQuotedArgs)

    val isRemote = sparkConfig.get("spark.submit.deployMode") contains "cluster"

    val allDriverOptions =
      sparkConfig.get("spark.driver.extraJavaOptions").toList ++ List("-Dlog4j.configuration=log4j.properties") mkString " "

    Seq("spark-submit", "--class", mainClass) ++
      Seq("--driver-java-options", allDriverOptions) ++
      sparkConfig.get("spark.driver.memory").toList.flatMap(mem => List("--driver-memory", mem)) ++
      (if (isRemote) Seq("--deploy-mode", "cluster") else Nil) ++
      sparkSubmitArgs ++
      sparkArgs ++ Seq(jarLocation) ++ serverArgs
  }
}
