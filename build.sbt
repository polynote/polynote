name := "polynote"

lazy val buildUI: TaskKey[Unit] = taskKey[Unit]("Building UI...")
lazy val runAssembly: TaskKey[Unit] = taskKey[Unit]("Running spark server from assembly...")

val versions = new {
  val http4s     = "0.20.6"
  val fs2        = "1.0.5"
  val catsEffect = "1.3.1"
  val coursier   = "2.0.0-RC2-6"
  val circe      = "0.11.1"
  val circeYaml  = "0.10.0"
  val spark      = "2.1.1"
}

def nativeLibraryPath = s"${sys.env.get("JAVA_LIBRARY_PATH") orElse sys.env.get("LD_LIBRARY_PATH") orElse sys.env.get("DYLD_LIBRARY_PATH") getOrElse "."}:."

val commonSettings = Seq(
  version := "0.1.23-SNAPSHOT",
  scalaVersion := "2.11.11",
  scalacOptions ++= Seq(
    "-Ypartial-unification",
    "-language:higherKinds",
    "-unchecked"
  ),
  fork in Test := true,
  javaOptions in Test += s"-Djava.library.path=$nativeLibraryPath",
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
  cancelable in Global := true,
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
    "org.scodec" %% "scodec-core" % "1.10.3",
    "io.circe" %% "circe-yaml" % versions.circeYaml,
    "io.circe" %% "circe-generic" % versions.circe,
    "io.circe" %% "circe-generic-extras" % versions.circe,
    "io.get-coursier" %% "coursier" % versions.coursier,
    "io.get-coursier" %% "coursier-cache" % versions.coursier,
    "io.get-coursier" %% "coursier-cats-interop" % versions.coursier,
    "org.scala-lang.modules" %% "scala-collection-compat" % "2.1.1",
    "org.apache.ivy" % "ivy" % "2.4.0" % "provided",
  )
).dependsOn(`polynote-runtime`)

val `polynote-env` = project.settings(
  commonSettings,
  scalacOptions += "-language:experimental.macros",
  libraryDependencies ++= Seq(
    "dev.zio" %% "zio-interop-cats" % "1.3.1.0-RC3" % "provided",
    "org.scala-lang" % "scala-reflect" % scalaVersion.value % "provided"
  )
)

val `polynote-kernel-zio` = project.settings(
  commonSettings,
  scalaVersion := "2.11.11",
  libraryDependencies ++= Seq(
    "org.scala-lang" % "scala-compiler" % scalaVersion.value % "provided",
    "org.scala-lang" % "scala-compiler" % scalaVersion.value % "test",
    "org.typelevel" %% "cats-effect" % versions.catsEffect,
    "dev.zio" %% "zio-interop-cats" % "1.3.1.0-RC3",
    "co.fs2" %% "fs2-io" % versions.fs2,
    "org.scodec" %% "scodec-core" % "1.10.3",
    "io.circe" %% "circe-yaml" % versions.circeYaml,
    "io.circe" %% "circe-generic" % versions.circe,
    "io.circe" %% "circe-generic-extras" % versions.circe,
    "io.get-coursier" %% "coursier" % versions.coursier,
    "io.get-coursier" %% "coursier-cache" % versions.coursier,
    "io.get-coursier" %% "coursier-cats-interop" % versions.coursier,
    "org.scala-lang.modules" %% "scala-collection-compat" % "2.1.1"
  )
).dependsOn(`polynote-runtime`, `polynote-kernel`, `polynote-env`)

val `polynote-server` = project.settings(
  commonSettings,
  libraryDependencies ++= Seq(
    "org.scala-lang" % "scala-compiler" % scalaVersion.value % "provided",
    "org.http4s" %% "http4s-core" % versions.http4s,
    "org.http4s" %% "http4s-dsl" % versions.http4s,
    "org.http4s" %% "http4s-blaze-server" % versions.http4s,
    "org.http4s" %% "http4s-blaze-client" % versions.http4s,
    "org.scodec" %% "scodec-core" % "1.10.3",
    "io.circe" %% "circe-parser" % versions.circe,
    "com.vladsch.flexmark" % "flexmark" % "0.34.32",
    "com.vladsch.flexmark" % "flexmark-ext-yaml-front-matter" % "0.34.32",
    "org.slf4j" % "slf4j-simple" % "1.7.25"
  ),
  unmanagedResourceDirectories in Compile += (ThisBuild / baseDirectory).value / "polynote-frontend" / "dist"
) dependsOn `polynote-kernel` % "compile->compile;test->test"

val `polynote-server-zio` = project.settings(
  commonSettings,
  libraryDependencies ++= Seq(
    "org.scala-lang" % "scala-compiler" % scalaVersion.value % "provided",
    "org.http4s" %% "http4s-core" % versions.http4s,
    "org.http4s" %% "http4s-dsl" % versions.http4s,
    "org.http4s" %% "http4s-blaze-server" % versions.http4s,
    "org.http4s" %% "http4s-blaze-client" % versions.http4s,
    "org.scodec" %% "scodec-core" % "1.10.3",
    "io.circe" %% "circe-parser" % versions.circe,
    "com.vladsch.flexmark" % "flexmark" % "0.34.32",
    "com.vladsch.flexmark" % "flexmark-ext-yaml-front-matter" % "0.34.32",
    "org.slf4j" % "slf4j-simple" % "1.7.25"
  ),
  unmanagedResourceDirectories in Compile += (ThisBuild / baseDirectory).value / "polynote-frontend" / "dist"
).dependsOn(`polynote-kernel-zio` % "compile->compile;test->test", `polynote-server`)

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
    "org.apache.spark" %% "spark-sql" % versions.spark % "provided",
    "org.apache.spark" %% "spark-repl" % versions.spark % "provided",
    "org.apache.spark" %% "spark-sql" % versions.spark % "test",
    "org.apache.spark" %% "spark-repl" % versions.spark % "test"
  )
) dependsOn `polynote-runtime`

lazy val `polynote-spark` = project.settings(
  commonSettings,
  libraryDependencies ++= Seq(
    "org.scala-lang" % "scala-compiler" % scalaVersion.value % "provided",
    "org.scodec" %% "scodec-stream" % "1.2.0",
    "org.apache.spark" %% "spark-sql" % versions.spark % "provided",
    "org.apache.spark" %% "spark-repl" % versions.spark % "provided",
    "org.apache.spark" %% "spark-sql" % versions.spark % "test",
    "org.apache.spark" %% "spark-repl" % versions.spark % "test",
  ),
  assemblyOption in assembly := (assemblyOption in assembly).value.copy(includeScala = false),
  resourceGenerators in Compile += Def.task {
    Seq(
      copyRuntimeJar((resourceManaged in Compile).value, "polynote-runtime.jar", (packageBin in (`polynote-runtime`, Compile)).value),
      copyRuntimeJar((resourceManaged in Compile).value, "polynote-spark-runtime.jar", (packageBin in (`polynote-spark-runtime`, Compile)).value),
      // sneak these scala dependency jars into the assembly so we have them if we need them (but they won't conflict with environment-provided jars)
      copyRuntimeJar((resourceManaged in Compile).value, "scala-library.jar", (dependencyClasspath in Compile).value.files.find(_.getName.contains("scala-library")).get), 
      copyRuntimeJar((resourceManaged in Compile).value, "scala-reflect.jar", (dependencyClasspath in Compile).value.files.find(_.getName.contains("scala-reflect")).get)
    )
  }.taskValue,
  fork in Test := true,
  parallelExecution in Test := false,
  mainClass in (runAssembly in Compile) := None,
  test in assembly := {},
  javaOptions in runAssembly := Seq(s"-Djava.library.path=$nativeLibraryPath"),
  runAssembly := {
    val assemblyJar = assembly.value
    val mainClassName = (mainClass in runAssembly).value.getOrElse("polynote.server.SparkServer")
    val log = streams.value.log
    val deps = (dependencyClasspath in Compile).value.files
    val scalaDeps = deps.filter(_.getName.matches(".*scala-(library|reflect|compiler|collection-compat|xml).*")).toList
    println(scalaDeps)
    val fo = ForkOptions()
      .withRunJVMOptions((javaOptions in runAssembly).value.toVector)
      .withWorkingDirectory(baseDirectory.value.getParentFile)
    val exit = new ForkRun(fo).fork(mainClassName, assemblyJar :: scalaDeps, Nil, log).exitValue()
    log.info(s"Assembly run exited with $exit")
  }
) dependsOn (`polynote-server` % "compile->compile;test->test", `polynote-spark-runtime`)

lazy val `polynote-spark-zio` = project.settings(
  commonSettings,
  libraryDependencies ++= Seq(
    "org.scala-lang" % "scala-compiler" % scalaVersion.value % "provided",
    "org.scodec" %% "scodec-stream" % "1.2.0",
    "org.apache.spark" %% "spark-sql" % versions.spark % "provided",
    "org.apache.spark" %% "spark-repl" % versions.spark % "provided",
    "org.apache.spark" %% "spark-sql" % versions.spark % "test",
    "org.apache.spark" %% "spark-repl" % versions.spark % "test",
  ),
  assemblyOption in assembly := (assemblyOption in assembly).value.copy(includeScala = false),
  resourceGenerators in Compile += Def.task {
    Seq(
      copyRuntimeJar((resourceManaged in Compile).value, "polynote-runtime.jar", (packageBin in (`polynote-runtime`, Compile)).value),
      copyRuntimeJar((resourceManaged in Compile).value, "polynote-spark-runtime.jar", (packageBin in (`polynote-spark-runtime`, Compile)).value),
      // sneak these scala dependency jars into the assembly so we have them if we need them (but they won't conflict with environment-provided jars)
      copyRuntimeJar((resourceManaged in Compile).value, "scala-library.jar", (dependencyClasspath in Compile).value.files.find(_.getName.contains("scala-library")).get),
      copyRuntimeJar((resourceManaged in Compile).value, "scala-reflect.jar", (dependencyClasspath in Compile).value.files.find(_.getName.contains("scala-reflect")).get)
    )
  }.taskValue
) dependsOn (`polynote-server-zio` % "compile->compile;test->test", `polynote-spark`, `polynote-spark-runtime`)

lazy val polynote = project.in(file(".")).aggregate(`polynote-kernel`, `polynote-server`, `polynote-spark`)
