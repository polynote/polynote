name := "polynote"

lazy val buildUI: TaskKey[Unit] = taskKey[Unit]("Building UI...")
lazy val runAssembly: TaskKey[Unit] = taskKey[Unit]("Running spark server from assembly...")
lazy val dist: TaskKey[File] = taskKey[File]("Building distribution...")
lazy val dependencyJars: TaskKey[Seq[(File, String)]] = taskKey("Dependency JARs which aren't included in the assembly")
lazy val polynoteJars: TaskKey[Seq[(File, String)]] = taskKey("Polynote JARs")

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
  version := "0.2.0-SNAPSHOT",
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
    case PathList(_, "BuildInfo$.class") => MergeStrategy.discard
    case x =>
      val oldStrategy = (assemblyMergeStrategy in assembly).value
      oldStrategy(x)
  },
  cancelable in Global := true,
  addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.7"),
  buildUI := {
    sys.process.Process(Seq("npm", "run", "build"), new java.io.File("./polynote-frontend/")) ! streams.value.log
  },
  test in assembly := {}
)

lazy val `polynote-runtime` = project.settings(
  commonSettings,
  scalacOptions ++= Seq(
    "-language:experimental.macros"
  ),
  libraryDependencies ++= Seq(
    "black.ninia" % "jep" % "3.9.0",
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


val `polynote-env` = project.settings(
  commonSettings,
  scalacOptions += "-language:experimental.macros",
  libraryDependencies ++= Seq(
    "dev.zio" %% "zio-interop-cats" % "1.3.1.0-RC3" % "provided",
    "org.scala-lang" % "scala-reflect" % scalaVersion.value % "provided"
  )
)

val `polynote-kernel` = project.settings(
  commonSettings,
  scalaVersion := "2.11.11",
  libraryDependencies ++= Seq(
    "org.scala-lang" % "scala-compiler" % scalaVersion.value % "provided",
    "org.scala-lang" % "scala-compiler" % scalaVersion.value % "test",
    "org.typelevel" %% "cats-effect" % versions.catsEffect,
    "dev.zio" %% "zio-interop-cats" % "1.3.1.0-RC3",
    "co.fs2" %% "fs2-io" % versions.fs2,
    "org.scodec" %% "scodec-core" % "1.10.3",
    "org.scodec" %% "scodec-stream" % "1.2.0",
    "com.lihaoyi" %% "fansi" % "0.2.6",
    "io.circe" %% "circe-yaml" % versions.circeYaml,
    "io.circe" %% "circe-generic" % versions.circe,
    "io.circe" %% "circe-generic-extras" % versions.circe,
    "io.get-coursier" %% "coursier" % versions.coursier,
    "io.get-coursier" %% "coursier-cache" % versions.coursier,
    "io.get-coursier" %% "coursier-cats-interop" % versions.coursier,
    "org.scala-lang.modules" %% "scala-collection-compat" % "2.1.1",
    "org.scala-lang.modules" %% "scala-java8-compat" % "0.9.0",
    "org.scalamock" %% "scalamock" % "4.4.0" % "test"
  )
).dependsOn(`polynote-runtime` % "provided", `polynote-runtime` % "test", `polynote-env`)

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
).dependsOn(`polynote-runtime` % "provided", `polynote-runtime` % "test", `polynote-kernel` % "compile->compile;test->test")

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
    "org.apache.spark" %% "spark-repl" % versions.spark % "test"
  ),
  dependencyJars := {
    (dependencyClasspath in (`polynote-kernel`, Compile)).value.collect {
      case jar if jar.data.name.matches(".*scala-(library|reflect|compiler|collection-compat|xml).*") =>
        jar.data -> s"polynote/deps/${jar.data.name}"
    }
  },
  polynoteJars := {
    val sparkAssembly    = (assemblyOutputPath in assembly).value
    val runtimeAssembly  = (assembly in `polynote-runtime`).value
    val sparkRuntime     = (assembly in `polynote-spark-runtime`).value
    List(
      sparkAssembly   -> "polynote/polynote.jar",
      runtimeAssembly -> "polynote/deps/polynote-runtime.jar",
      sparkRuntime    -> "polynote/deps/polynote-spark-runtime.jar")
  },
  assemblyOption in assembly := {
    val jars = (polynoteJars.value ++ dependencyJars.value).map(_._2.stripPrefix("polynote/")).mkString(":")
    (assemblyOption in assembly).value.copy(
      includeScala = false,
      prependShellScript = Some(
        IO.read(file(".") / "scripts/polynote").lines.toSeq
      ))
  }
) dependsOn (
  `polynote-server` % "compile->compile;test->test",
  `polynote-spark-runtime` % "provided",
  `polynote-spark-runtime` % "test",
  `polynote-runtime` % "provided",
  `polynote-runtime` % "test")

lazy val polynote = project.in(file(".")).aggregate(`polynote-kernel`, `polynote-server`, `polynote-spark`).settings(
  dist := {
    val jars = (polynoteJars in `polynote-spark`).value ++ (dependencyJars in `polynote-spark`).value
    val mainJar = (assembly in `polynote-spark`).value
    //val outFile = crossTarget.value / "polynote-dist.zip"
    val tarFile = crossTarget.value / "polynote-dist.tar"
    val outFile = crossTarget.value / "polynote-dist.tar.gz"

    if (tarFile.exists())
      tarFile.delete()

    if(outFile.exists())
      outFile.delete()

    val files = jars ++ List(
      (file(".") / "config-template.yml") -> "polynote/config-template.yml",
      (file(".") / "scripts" / "polynote") -> "polynote/polynote",
      (file(".") / "scripts" / "plugin") -> "polynote/plugin")

    // build a .tar.gz by invoking command-line tools. Sorry, Windows.
    val targetDir = crossTarget.value / "polynote"
    targetDir.mkdirs()

    val resolvedFiles = files.map {
      case (srcFile, targetPath) => srcFile -> (crossTarget.value / targetPath)
    }

    IO.copy(resolvedFiles, overwrite = true, preserveLastModified = true, preserveExecutable = true)

    val rootPath = crossTarget.value.toPath
    def relative(file: File): String = rootPath.relativize(file.toPath).toString

    import sys.process.stringSeqToProcess
    Seq("tar", "-cf", tarFile.toString, "-C", crossTarget.value.toString, "polynote").!
    Seq("gzip", "-S", ".gz", tarFile.toString).!


//    // simpler than all the above, but doesn't preserve executable status
//    IO.zip(
//      files,
//      outFile)

    outFile
  },
  commonSettings
)
