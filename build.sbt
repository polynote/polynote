name := "polynote"

lazy val buildUI: TaskKey[Unit] = taskKey[Unit]("Building UI...")

val commonSettings = Seq(
  version := "0.1.0-SNAPSHOT",
  scalaVersion := "2.11.11",
  scalacOptions ++= Seq(
    "-Ypartial-unification",
    "-language:higherKinds",
    "-unchecked"
  ),
  libraryDependencies ++= Seq(
    "org.scalatest" %% "scalatest" % "3.0.5" % "test",
    "org.scalacheck" %% "scalacheck" % "1.14.0" % "test"
  ),
  fork := true,
  assemblyMergeStrategy in assembly := {
    case PathList("META-INF", "CHANGES") => MergeStrategy.discard
    case x =>
      val oldStrategy = (assemblyMergeStrategy in assembly).value
      oldStrategy(x)
  },
  addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.7"),
  buildUI := {
    sys.process.Process(Seq("npm", "run", "build"), new java.io.File("./polynote-frontend/")) ! streams.value.log
  }
)

val `polynote-runtime` = project.settings(
  commonSettings,
  libraryDependencies ++= Seq(
    "black.ninia" % "jep" % "3.8.2"
  )
)

val `polynote-kernel` = project.settings(
  commonSettings,
  scalaVersion := "2.11.11",
  libraryDependencies ++= Seq(
    "org.scala-lang" % "scala-compiler" % scalaVersion.value % "provided",
    "org.scala-lang" % "scala-compiler" % scalaVersion.value % "test",
    "org.typelevel" %% "cats-effect" % "1.0.0",
    "co.fs2" %% "fs2-io" % "1.0.0-M5",
    "org.log4s" %% "log4s" % "1.6.1",
    "org.scodec" %% "scodec-core" % "1.10.3",
    "io.circe" %% "circe-yaml" % "0.9.0",
    "io.circe" %% "circe-generic" % "0.10.0",
    "io.get-coursier" %% "coursier" % "1.1.0-M9",
    "io.get-coursier" %% "coursier-cache" % "1.1.0-M9"
  )
).dependsOn(`polynote-runtime`)

val `polynote-server` = project.settings(
  commonSettings,
  libraryDependencies ++= Seq(
    "org.scala-lang" % "scala-compiler" % scalaVersion.value % "provided",
    "org.http4s" %% "http4s-core" % "0.19.0-M2",
    "org.http4s" %% "http4s-dsl" % "0.19.0-M2",
    "org.http4s" %% "http4s-blaze-server" % "0.19.0-M2",
    "org.scodec" %% "scodec-core" % "1.10.3",
    "io.circe" %% "circe-generic-extras" % "0.10.0",
    "io.circe" %% "circe-parser" % "0.10.0",
    "com.vladsch.flexmark" % "flexmark" % "0.34.32",
    "com.vladsch.flexmark" % "flexmark-ext-yaml-front-matter" % "0.34.32",
    "org.slf4j" % "slf4j-simple" % "1.7.25"
  ),
  unmanagedResourceDirectories in Compile += (ThisBuild / baseDirectory).value / "polynote-frontend" / "dist"
) dependsOn `polynote-kernel`

val `polynote-spark` = project.settings(
  commonSettings,
  libraryDependencies ++= Seq(
    "org.scala-lang" % "scala-compiler" % scalaVersion.value % "provided",
    "org.apache.spark" %% "spark-sql" % "2.1.1" % "provided",
    "org.apache.spark" %% "spark-repl" % "2.1.1" % "provided",
    "org.apache.spark" %% "spark-sql" % "2.1.1" % "test",
    "org.apache.spark" %% "spark-repl" % "2.1.1" % "test",
  ),
  assemblyOption in assembly := (assemblyOption in assembly).value.copy(includeScala = false)
) dependsOn `polynote-server`

val polynote = project.in(file(".")).aggregate(`polynote-kernel`, `polynote-server`)