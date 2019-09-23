package polynote.config

import cats.syntax.either._
import io.circe._
import io.circe.syntax._
import org.scalatest._

class PolynoteConfigSpec extends FlatSpec with Matchers with EitherValues {

  "PolynoteConfig" should "Ser/De" in {

    val cfg = PolynoteConfig(
      Listen(), Storage(), List(maven("foo")), List("exclude!"), Map("foo" -> List("bar", "baz")), Map("key" -> "val")
    )
    val js = cfg.asJson
    val cfgString = cfg.asJson.spaces2
    yaml.parser.parse(cfgString).flatMap(_.as[PolynoteConfig]).right.value shouldEqual cfg

  }

  it should "parse handwritten yamls" in {

    val yamlStr =
      """
        |# The host and port can be set by uncommenting and editing the following lines:
        |listen:
        |  host: 1.1.1.1
        |  port: 8193
        |
        |storage:
        |  dir: foo
        |
        |# Default repositories can be specified. Uncommenting the following lines would add four default repositories which are inherited by new notebooks.
        |repositories:
        |  - ivy:
        |      base: https://my-artifacts.org/artifacts/
        |  - ivy:
        |      base: https://my-custom-ivy-repo.org/artifacts/
        |      artifact_pattern: "[orgPath]/[module](_[scalaVersion])(_[sbtVersion])/[revision]/[artifact]-[revision](-[classifier]).[ext]"
        |      metadata_pattern: "[orgPath]/[module](_[scalaVersion])(_[sbtVersion])/[revision]/[module](_[scalaVersion])(_[sbtVersion])-[revision]-ivy.xml"
        |      changing: true
        |  - maven:
        |      base: http://central.maven.org/maven2/
        |  - maven:
        |      base: http://oss.sonatype.org/content/repositories/snapshots
        |      changing: true
        |
        |# Default dependencies can be specified. Uncommenting the following lines would add default dependencies for the Scala interpreter, which are inherited by new notebooks.
        |dependencies:
        |  scala:
        |    - org.typelevel:cats-core_2.11:1.6.0
        |    - com.mycompany:my-library:jar:all:1.0.0
        |exclusions:
        |  - org.typelevel
        |  - com.mycompany
        |
        |# Spark config params can be set by uncommenting and editing the following lines:
        |spark:
        |  spark.driver.userClasspathFirst: true
        |  spark.executor.userClasspathFirst: true
        |
      """.stripMargin

    val parsed = yaml.parser.parse(yamlStr).flatMap(_.as[PolynoteConfig])

    parsed.right.value shouldEqual PolynoteConfig(
      Listen(
        host = "1.1.1.1",
        port = 8193
      ),
      Storage("foo"),
      List(
        ivy("https://my-artifacts.org/artifacts/"),
        ivy(
          "https://my-custom-ivy-repo.org/artifacts/",
          artifactPatternOpt = Option("[orgPath]/[module](_[scalaVersion])(_[sbtVersion])/[revision]/[artifact]-[revision](-[classifier]).[ext]"),
          metadataPatternOpt = Option("[orgPath]/[module](_[scalaVersion])(_[sbtVersion])/[revision]/[module](_[scalaVersion])(_[sbtVersion])-[revision]-ivy.xml"),
          changing = Option(true)
        ),
        maven("http://central.maven.org/maven2/"),
        maven("http://oss.sonatype.org/content/repositories/snapshots", changing = Option(true))
      ),
      List("org.typelevel", "com.mycompany"),
      Map("scala" -> List("org.typelevel:cats-core_2.11:1.6.0", "com.mycompany:my-library:jar:all:1.0.0")),
      Map("spark.driver.userClasspathFirst" -> "true", "spark.executor.userClasspathFirst" -> "true")
    )

  }
}
