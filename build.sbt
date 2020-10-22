name := "polynote"

val buildUI: TaskKey[Unit] = taskKey[Unit]("Building UI...")
val distUI: TaskKey[Unit] = taskKey[Unit]("Building UI for distribution...")
val runAssembly: TaskKey[Unit] = taskKey[Unit]("Running spark server from assembly...")
val dist: TaskKey[File] = taskKey[File]("Building distribution...")
val dependencyJars: TaskKey[Seq[(File, String)]] = taskKey("Dependency JARs which aren't included in the assembly")
val polynoteJars: TaskKey[Seq[(File, String)]] = taskKey("Polynote JARs")
val sparkVersion: SettingKey[String] = settingKey("Spark version")

val versions = new {
  val fs2        = "1.0.5"
  val catsEffect = "2.0.0"
  val coursier   = "2.0.0-RC2-6"
  val postgresVersion = "42.2.16"
  val quillVersion    = "3.5.2"
  val zio        = "1.0.2"
  val zioInterop = "2.0.0.0-RC12"
}

def nativeLibraryPath = s"${sys.env.get("JAVA_LIBRARY_PATH") orElse sys.env.get("LD_LIBRARY_PATH") orElse sys.env.get("DYLD_LIBRARY_PATH") getOrElse "."}:."

val commonSettings = Seq(
  scalaVersion := "2.11.12",
  crossScalaVersions := Seq("2.11.12", "2.12.10"),
  organization := "org.polynote",
  publishMavenStyle := true,
  homepage := Some(url("https://polynote.org")),
  licenses := Seq("APL2" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt")),
  scmInfo := Some(
    ScmInfo(
      url("https://github.com/polynote/polynote"),
      "scm:git@github.com:polynote/polynote.git"
    )
  ),
  version := "0.3.12",
  publishTo := sonatypePublishToBundle.value,
  developers := List(
    Developer(id = "jeremyrsmith", name = "Jeremy Smith", email = "", url = url("https://github.com/jeremyrsmith")),
    Developer(id = "jonathanindig", name = "Jonathan Indig", email = "", url = url("https://github.com/jonathanindig"))
  ),
  scalacOptions ++= Seq(
    "-Ypartial-unification",
    "-language:higherKinds",
    "-unchecked"
  ),
  fork in Test := true,
  javaOptions in Test += s"-Djava.library.path=$nativeLibraryPath",
  libraryDependencies ++= Seq(
    "org.scalatest" %% "scalatest" % "3.0.8" % "test",
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
  assemblyOption in assembly := {
    (assemblyOption in assembly).value.copy(includeScala = false)
  },
  cancelable in Global := true,
  addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.10.3"),
  buildUI := {
    sys.process.Process(Seq("npm", "run", "build"), new java.io.File("./polynote-frontend/")) ! streams.value.log
  },
  distUI := {
    sys.process.Process(Seq("npm", "run", "clean"), new java.io.File("./polynote-frontend/")) ! streams.value.log
    sys.process.Process(Seq("npm", "run", "dist"), new java.io.File("./polynote-frontend/")) ! streams.value.log
  },
  scalacOptions += "-deprecation",
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
    //"dev.zio" %% "zio-interop-cats" % versions.zioInterop % "provided",
    "dev.zio" %% "zio" % versions.zio,
    "org.scala-lang" % "scala-reflect" % scalaVersion.value % "provided"
  )
)

val `polynote-kernel` = project.settings(
  commonSettings,
  libraryDependencies ++= Seq(
    "org.scala-lang" % "scala-compiler" % scalaVersion.value % "provided",
    "org.scala-lang" % "scala-compiler" % scalaVersion.value % "test",
    "org.typelevel" %% "cats-effect" % versions.catsEffect,
    "dev.zio" %% "zio" % versions.zio,
    "dev.zio" %% "zio-streams" % versions.zio,
    //"dev.zio" %% "zio-interop-cats" % versions.zioInterop,
    "co.fs2" %% "fs2-io" % versions.fs2,
    "org.scodec" %% "scodec-core" % "1.11.4",
    "org.scodec" %% "scodec-stream" % "1.2.0",
    "io.get-coursier" %% "coursier" % versions.coursier,
    "io.get-coursier" %% "coursier-cache" % versions.coursier,
    "io.github.classgraph" % "classgraph" % "4.8.47",
    "org.scala-lang.modules" %% "scala-collection-compat" % "2.1.1",
    "org.scala-lang.modules" %% "scala-java8-compat" % "0.9.0",
    "io.circe" %% "circe-yaml" % "0.10.0",
    "io.circe" %% "circe-generic" % "0.11.1",
    "io.circe" %% "circe-generic-extras" % "0.11.1",
    "io.circe" %% "circe-parser" % "0.11.1",
    "net.sf.py4j" % "py4j" % "0.10.7",
    "org.scalamock" %% "scalamock" % "4.4.0" % "test"
  ),
  coverageExcludedPackages := "polynote\\.kernel\\.interpreter\\.python\\..*;polynote\\.runtime\\.python\\..*" // see https://github.com/scoverage/scalac-scoverage-plugin/issues/176
).dependsOn(`polynote-runtime` % "provided", `polynote-runtime` % "test", `polynote-env`)

val `polynote-server` = project.settings(
  commonSettings,
  libraryDependencies ++= Seq(
    "org.scala-lang" % "scala-compiler" % scalaVersion.value % "provided",
    "io.getquill" %% "quill-jdbc" % versions.quillVersion,
    "org.polynote" %% "uzhttp" % "0.2.6",
    "org.postgresql" % "postgresql" % versions.postgresVersion,
    "org.scodec" %% "scodec-core" % "1.10.3",
    "com.typesafe" % "config" % "1.4.0",
    "com.vladsch.flexmark" % "flexmark" % "0.34.32",
    "com.vladsch.flexmark" % "flexmark-ext-yaml-front-matter" % "0.34.32",
    "org.slf4j" % "slf4j-simple" % "1.7.25"
  ),
  //unmanagedResourceDirectories in Compile += (ThisBuild / baseDirectory).value / "polynote-frontend" / "dist",
  packageBin := {
    val _ = distUI.value
    (packageBin in Compile).value
  }
).dependsOn(`polynote-runtime` % "provided", `polynote-runtime` % "test", `polynote-kernel` % "compile->compile;test->test")

val sparkSettings = Seq(
  sparkVersion := {
    scalaVersion.value match {
      case ver if ver startsWith "2.11" => "2.1.1"
      case ver                          => "2.4.4"  // Spark 2.4 is first version to publish for scala 2.12
    }
  },
  libraryDependencies ++= Seq(
    "org.apache.spark" %% "spark-sql" % sparkVersion.value % "provided",
    "org.apache.spark" %% "spark-repl" % sparkVersion.value % "provided",
    "org.apache.spark" %% "spark-sql" % sparkVersion.value % "test",
    "org.apache.spark" %% "spark-repl" % sparkVersion.value % "test"
  )
)

lazy val `polynote-spark-runtime` = project.settings(
  commonSettings,
  sparkSettings,
  libraryDependencies ++= Seq(
    "org.scala-lang" % "scala-compiler" % scalaVersion.value % "provided"
  )
) dependsOn `polynote-runtime`

lazy val `polynote-spark` = project.settings(
  commonSettings,
  sparkSettings,
  libraryDependencies ++= Seq(
    "org.scala-lang" % "scala-compiler" % scalaVersion.value % "provided",
    "org.scodec" %% "scodec-stream" % "1.2.0"
  ),
  dependencyJars := {
    (dependencyClasspath in (`polynote-kernel`, Compile)).value.collect {
      case jar if jar.data.name.matches(".*scala-(library|reflect|compiler|collection-compat|xml).*") =>
        jar.data -> s"polynote/deps/${jar.data.name}"
    }
  },
  polynoteJars := {
    val runtimeAssembly  = (assembly in `polynote-runtime`).value
    val sparkRuntime     = (assembly in `polynote-spark-runtime`).value
    List(
      runtimeAssembly -> "polynote/deps/polynote-runtime.jar",
      sparkRuntime    -> "polynote/deps/polynote-spark-runtime.jar")
  },
  assemblyOption in assembly := {
    (assemblyOption in assembly).value.copy(
      includeScala = false,
      prependShellScript = Some(
        IO.read(file(".") / "scripts/polynote").linesIterator.toSeq
      ))
  }
) dependsOn (
  `polynote-server` % "compile->compile;test->test",
  `polynote-spark-runtime` % "provided",
  `polynote-spark-runtime` % "test",
  `polynote-runtime` % "provided",
  `polynote-runtime` % "test")

lazy val polynote = project.in(file(".")).aggregate(`polynote-env`, `polynote-runtime`, `polynote-spark-runtime`, `polynote-kernel`, `polynote-server`, `polynote-spark`)
    .settings(
      commonSettings,
      dist := {
        val jars = ((assembly in (`polynote-spark`, Compile)).value -> "polynote/polynote.jar") +:
          ((polynoteJars in (`polynote-spark`, Compile)).value ++ (dependencyJars in (`polynote-spark`, Compile)).value)

        val examples = IO.listFiles(file(".") / "docs" / "examples").map(f => (f, s"polynote/examples/${f.getName}"))

        val versionTag = scalaBinaryVersion.value match {
          case "2.11" => ""
          case ver    => s"-$ver"
        }

        val tarFile = crossTarget.value / s"polynote-dist$versionTag.tar"
        val outFile = crossTarget.value / s"polynote-dist$versionTag.tar.gz"

        if (tarFile.exists())
          tarFile.delete()

        if(outFile.exists())
          outFile.delete()

        val files = jars ++ examples ++ List(
          (file(".") / "config-template.yml") -> "polynote/config-template.yml",
          (file(".") / "requirements.txt") -> "polynote/requirements.txt",
          (file(".") / "scripts" / "plugin") -> "polynote/plugin",
          (file(".") / "scripts" / "polynote.py") -> "polynote/polynote.py")

        // build a .tar.gz by invoking command-line tools. Sorry, Windows.
        val targetDir = crossTarget.value / "polynote"
        targetDir.mkdirs()

        val resolvedFiles = files.map {
          case (srcFile, targetPath) => srcFile -> (crossTarget.value / targetPath)
        }

        IO.copy(resolvedFiles, overwrite = true, preserveLastModified = true, preserveExecutable = true)

        IO.copyDirectory(file(".") / "polynote-frontend" / "dist" / "static", targetDir / "static")

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
      }
    )

