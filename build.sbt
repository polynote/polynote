name := "polynote"

lazy val buildUI: TaskKey[Unit] = taskKey[Unit]("Building UI...")

val versions = new {
  val http4s     = "0.20.0-M6"
  val fs2        = "1.0.3"
  val catsEffect = "1.2.0"
}

val commonSettings = Seq(
  version := "0.1.6-SNAPSHOT",
  scalaVersion := "2.11.11",
  scalacOptions ++= Seq(
    "-Ypartial-unification",
    "-language:higherKinds",
    "-unchecked"
  ),
  fork in Test := true,
  javaOptions in Test += s"-Djava.library.path=${sys.env.get("JAVA_LIBRARY_PATH") orElse sys.env.get("LD_LIBRARY_PATH") orElse sys.env.get("DYLD_LIBRARY_PATH") getOrElse "."}",
  libraryDependencies ++= Seq(
    "org.scalatest" %% "scalatest" % "3.0.5" % "test",
    "org.scalacheck" %% "scalacheck" % "1.14.0" % "test"
  ),
  assemblyMergeStrategy in assembly := {
    case PathList("META-INF", "CHANGES") => MergeStrategy.discard
    case PathList("coursier", "shaded", xs @ _*) => MergeStrategy.first // coursier shades some of the same classes. assembly somehow can't dedupe even though they seem identical to me. 
    case x =>
      val oldStrategy = (assemblyMergeStrategy in assembly).value
      oldStrategy(x)
  },
  addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.7"),
  buildUI := {
    sys.process.Process(Seq("npm", "run", "build"), new java.io.File("./polynote-frontend/")) ! streams.value.log
  }
)

lazy val `polynote-runtime` = project.settings(
  commonSettings,
  scalacOptions ++= Seq(
    "-language:experimental.macros"
  ),
  libraryDependencies ++= Seq(
    "black.ninia" % "jep" % "3.8.2",
    "com.chuusai" %% "shapeless" % "2.3.3",
    "org.scala-lang" % "scala-reflect" % scalaVersion.value % "provided"
  )
).enablePlugins(BuildInfoPlugin)
  .settings(
    buildInfoKeys := Seq[BuildInfoKey](
      name,
      version,
      BuildInfoKey.action("commit") {
        git.gitHeadCommit.value.getOrElse("unknown")
      },
      BuildInfoKey.action("buildTime") {
        System.currentTimeMillis
      }
    ),
    buildInfoPackage := "polynote.buildinfo"
  )

val `polynote-kernel` = project.settings(
  commonSettings,
  scalaVersion := "2.11.11",
  libraryDependencies ++= Seq(
    "org.scala-lang" % "scala-compiler" % scalaVersion.value % "provided",
    "org.scala-lang" % "scala-compiler" % scalaVersion.value % "test",
    "org.typelevel" %% "cats-effect" % versions.catsEffect,
    "co.fs2" %% "fs2-io" % versions.fs2,
    "org.log4s" %% "log4s" % "1.6.1",
    "org.scodec" %% "scodec-core" % "1.10.3",
    "io.circe" %% "circe-yaml" % "0.9.0",
    "io.circe" %% "circe-generic" % "0.10.0",
    "io.circe" %% "circe-generic-extras" % "0.10.0",
    "io.get-coursier" %% "coursier" % "1.1.0-M14-1",
    "io.get-coursier" %% "coursier-cache" % "1.1.0-M14-1",
    "io.get-coursier" %% "coursier-cats-interop" % "1.1.0-M14-1",
    "org.apache.ivy" % "ivy" % "2.4.0" % "provided"
  )
).dependsOn(`polynote-runtime`)

val `polynote-server` = project.settings(
  commonSettings,
  libraryDependencies ++= Seq(
    "org.scala-lang" % "scala-compiler" % scalaVersion.value % "provided",
    "org.http4s" %% "http4s-core" % versions.http4s,
    "org.http4s" %% "http4s-dsl" % versions.http4s,
    "org.http4s" %% "http4s-blaze-server" % versions.http4s,
    "org.http4s" %% "http4s-blaze-client" % versions.http4s,
    "org.scodec" %% "scodec-core" % "1.10.3",
    "io.circe" %% "circe-parser" % "0.10.0",
    "com.vladsch.flexmark" % "flexmark" % "0.34.32",
    "com.vladsch.flexmark" % "flexmark-ext-yaml-front-matter" % "0.34.32",
    "org.slf4j" % "slf4j-simple" % "1.7.25"
  ),
  unmanagedResourceDirectories in Compile += (ThisBuild / baseDirectory).value / "polynote-frontend" / "dist"
) dependsOn `polynote-kernel`

def copyRuntimeJar(targetDir: File, targetName: String, file: File) = {
    val targetFile = targetDir / targetName
    targetDir.mkdirs()
    java.nio.file.Files.copy(file.toPath, targetFile.toPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    targetFile
}

lazy val `polynote-spark-runtime` = project.settings(
  commonSettings,
  libraryDependencies ++= Seq(
    "org.scala-lang" % "scala-compiler" % scalaVersion.value % "provided",
    "org.apache.spark" %% "spark-sql" % "2.1.1" % "provided",
    "org.apache.spark" %% "spark-repl" % "2.1.1" % "provided",
    "org.apache.spark" %% "spark-sql" % "2.1.1" % "test",
    "org.apache.spark" %% "spark-repl" % "2.1.1" % "test"
  )
) dependsOn `polynote-runtime`

lazy val `polynote-spark` = project.settings(
  commonSettings,
  libraryDependencies ++= Seq(
    "org.scala-lang" % "scala-compiler" % scalaVersion.value % "provided",
    "org.scodec" %% "scodec-stream" % "1.2.0",
    "org.apache.spark" %% "spark-sql" % "2.1.1" % "provided",
    "org.apache.spark" %% "spark-repl" % "2.1.1" % "provided",
    "org.apache.spark" %% "spark-sql" % "2.1.1" % "test",
    "org.apache.spark" %% "spark-repl" % "2.1.1" % "test",
  ),
  assemblyOption in assembly := (assemblyOption in assembly).value.copy(includeScala = false),
  resourceGenerators in Compile += Def.task {
    Seq(
      copyRuntimeJar((resourceManaged in Compile).value, "polynote-runtime.jar", (packageBin in (`polynote-runtime`, Compile)).value),
      copyRuntimeJar((resourceManaged in Compile).value, "polynote-spark-runtime.jar", (packageBin in (`polynote-spark-runtime`, Compile)).value),
      copyRuntimeJar((resourceManaged in Compile).value, "scala-library.jar", (dependencyClasspath in Compile).value.files.find(_.getName.contains("scala-library")).get) // sneak scala-lang jar into the assembly
    )
  }.taskValue,
  fork in Test := false,
  parallelExecution in Test := false
) dependsOn (`polynote-server`, `polynote-spark-runtime`)

val polynote = project.in(file(".")).aggregate(`polynote-kernel`, `polynote-server`, `polynote-spark`)