package polynote.buildinfo

import scala.util.Try

object SafeBuildInfo {
  private val buildInfoClass: Option[Class[_]] = Try(Class.forName("polynote.buildinfo.BuildInfo$")).toOption
  private val buildInfoModule = for (buildInfoClass <- buildInfoClass) yield buildInfoClass.getField("MODULE$").get(null)

  val name: String = (for (buildInfoClass <- buildInfoClass; buildInfoModule <- buildInfoModule) yield
    buildInfoClass.getMethod("name").invoke(buildInfoModule).asInstanceOf[String]
    ).getOrElse("polynote")

  val version: String = (for (buildInfoClass <- buildInfoClass; buildInfoModule <- buildInfoModule) yield
    buildInfoClass.getMethod("version").invoke(buildInfoModule).asInstanceOf[String]
    ).getOrElse("DEV")

  val commit: String = (for (buildInfoClass <- buildInfoClass; buildInfoModule <- buildInfoModule) yield
    buildInfoClass.getMethod("commit").invoke(buildInfoModule).asInstanceOf[String]
    ).getOrElse("---")

  val buildTime: scala.Long = (for (buildInfoClass <- buildInfoClass; buildInfoModule <- buildInfoModule) yield
    buildInfoClass.getMethod("buildTime").invoke(buildInfoModule).asInstanceOf[java.lang.Long].toLong
    ).getOrElse(System.currentTimeMillis())

  override val toString: String = {
    "name: %s, version: %s, commit: %s, buildTime: %s" format(
      name, version, commit, buildTime
    )
  }
}
