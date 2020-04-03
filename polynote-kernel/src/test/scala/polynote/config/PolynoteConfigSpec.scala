package polynote.config

import java.util.regex.Pattern

import cats.syntax.either._
import io.circe._
import io.circe.syntax._
import org.scalatest._

class PolynoteConfigSpec extends FlatSpec with Matchers with EitherValues {

  "PolynoteConfig" should "Ser/De" in {

    val cfg = PolynoteConfig(
      Listen(), KernelConfig(), Storage(), List(maven("foo")), List("exclude!"), Map("foo" -> List("bar", "baz")), Some(SparkConfig(Map("key" -> "val")))
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
        |# The host and port range for server/kernel communication
        |kernel:
        |  listen: 127.1.1.1
        |  port_range: "1000:2000"
        |storage:
        |  cache: tmp
        |  dir: notebooks
        |  mounts:
        |    examples:
        |      dir: examples
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
        |  sparkSubmitArgs: testing
        |
        |# Credentials. This list contains the list of credentials used to access the repositories
        |
        |credentials:
        |  coursier:
        |    path: ~/.config/coursier/credentials.properties
      """.stripMargin

    val parsed = yaml.parser.parse(yamlStr).flatMap(_.as[PolynoteConfig])

    parsed.right.value shouldEqual PolynoteConfig(
      Listen(
        host = "1.1.1.1",
        port = 8193
      ),
      KernelConfig(Some("127.1.1.1"), Some(Range.inclusive(1000, 2000))),
      Storage("tmp", dir = "notebooks", Map("examples" -> Mount("examples"))),
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
      Some(SparkConfig(Map("spark.driver.userClasspathFirst" -> "true", "spark.executor.userClasspathFirst" -> "true"), Some("testing"))),
      credentials = Credentials(
        coursier = Some(Credentials.Coursier("~/.config/coursier/credentials.properties"))
      )
    )

  }

  it should "parse new spark config" in {
    val yaml =
      """spark:
        |  properties:
        |    flurg: blurg
        |    foo:   bar
        |  spark_submit_args: these are the args
        |  dist_classpath_filter: .jar$
        |  property_sets:
        |    - name: Test
        |      properties:
        |        something: thing
        |        another:   one
        |      spark_submit_args: some more args
        |
        |    - name: Test 2
        |      properties:
        |        something: thing2
        |""".stripMargin

    // Pattern has no equals :(
    val parsed = PolynoteConfig.parse(yaml).right.get.spark.get
    parsed.properties shouldEqual Map("flurg" -> "blurg", "foo" -> "bar")
    parsed.sparkSubmitArgs shouldEqual Some("these are the args")
    parsed.distClasspathFilter.get.pattern() shouldEqual ".jar$"
    parsed.propertySets.get shouldEqual List(
      SparkPropertySet(name = "Test", properties = Map("something" -> "thing", "another" -> "one"), sparkSubmitArgs = Some("some more args"), None),
      SparkPropertySet(name = "Test 2", properties = Map("something" -> "thing2"))
    )
  }

  it should "fail on invalid configuration" in {
    val badYaml =
      """spark:
        |  dist_classpath_filter: not a regex**
        |""".stripMargin

    val Left(err) = PolynoteConfig.parse(badYaml)
    assert(err.getMessage contains "Configuration is invalid")
    assert(err.getMessage contains "Invalid regular expression")
  }

  it should "Parse Shared Classes" in {
    val yamlStr =
      """
        |behavior:
        |  shared_packages:
        |    - com.esotericsoftware.kryo
        |    - org.myclass
      """.stripMargin

    val parsed = PolynoteConfig.parse(yamlStr)

    parsed.right.value.behavior.sharedPackages shouldEqual List("com.esotericsoftware.kryo", "org.myclass")
    parsed.right.value.behavior.getSharedString shouldEqual
      "^(com.esotericsoftware.kryo|org.myclass|scala|javax?|jdk|sun|com.sun|com.oracle|polynote|org.w3c|org.xml|org.omg|org.ietf|org.jcp|org.apache.spark|org.spark_project|org.glassfish.jersey|org.jvnet.hk2|org.apache.hadoop|org.codehaus|org.slf4j|org.log4j|org.apache.log4j)\\."
  }

  it should "handle empty configurations" in {
    val yamlStr = ""

    val parsed = PolynoteConfig.parse(yamlStr)
    val defaultConfig = PolynoteConfig()
    parsed shouldEqual Right(defaultConfig)
  }

  it should "handle comment-only configurations" in {
    val yamlStr =
      """
        |# some yaml comment
        |# another comment
        |""".stripMargin

    val parsed = PolynoteConfig.parse(yamlStr)
    val defaultConfig = PolynoteConfig()
    parsed shouldEqual Right(defaultConfig)
  }

  it should "parse spark config int values properly" in {
    val yamlStr =
      """
        |spark:
        |  spark.executor.instances: 10
        |""".stripMargin
    val parsed = PolynoteConfig.parse(yamlStr)
    parsed.right.value.spark.get.properties("spark.executor.instances") shouldEqual "10"
  }

  it should "parse spark config decimal values properly" in {
    val yamlStr =
      """
        |spark:
        |  spark.some.decimal: 1.523432422343
        |""".stripMargin
    val parsed = PolynoteConfig.parse(yamlStr)
    parsed.right.value.spark.get.properties("spark.some.decimal") shouldEqual "1.523432422343"
  }
}
