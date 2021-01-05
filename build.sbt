name := "polynote"

val buildUI: TaskKey[Unit] = taskKey[Unit]("Building UI...")
val distUI: TaskKey[Unit] = taskKey[Unit]("Building UI for distribution...")
val runAssembly: TaskKey[Unit] = taskKey[Unit]("Running spark server from assembly...")
val distFiles: TaskKey[Seq[File]] = taskKey[Seq[File]]("Distribution files")
val prepDistFiles: TaskKey[Seq[File]] = taskKey[Seq[File]]("Prepare distribution files")
val dependencyJars: TaskKey[Seq[(File, String)]] = taskKey("Dependency JARs which aren't included in the assembly")
val polynoteJars: TaskKey[Seq[(File, String)]] = taskKey("Polynote JARs")
val sparkVersion: SettingKey[String] = settingKey("Spark version")

val versions = new {
  val fs2        = "1.0.5"
  val catsEffect = "2.0.0"
  val coursier   = "2.0.0-RC5-6"
  val zio        = "1.0.2"
  val zioInterop = "2.0.0.0-RC12"
}

def nativeLibraryPath = s"${sys.env.get("JAVA_LIBRARY_PATH") orElse sys.env.get("LD_LIBRARY_PATH") orElse sys.env.get("DYLD_LIBRARY_PATH") getOrElse "."}:."

val distBuildDir = file(".") / "target" / "dist" / "polynote"
val scalaVersions = Seq("2.11.12", "2.12.12")
lazy val scalaBinaryVersions = scalaVersions.map {
  ver => ver.split('.').take(2).mkString(".")
}.distinct

val commonSettings = Seq(
  scalaVersion := "2.11.12",
  crossScalaVersions := scalaVersions,
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
  distFiles := Nil,
  prepDistFiles := {
    val targetDir = distBuildDir / "deps" / scalaBinaryVersion.value
    targetDir.mkdirs()
    val sourceFiles = distFiles.value
    val destFiles = sourceFiles.map {
      file => targetDir / file.name
    }
    IO.copy(sourceFiles zip destFiles, overwrite = true, preserveLastModified = true, preserveExecutable = true)
    destFiles
  },
  scalacOptions += "-deprecation",
  test in assembly := {}
)

lazy val `polynote-macros` = project.settings(
  commonSettings,
  libraryDependencies ++= Seq("org.scala-lang" % "scala-reflect" % scalaVersion.value % "provided"),
  scalacOptions ++= Seq(
    "-language:experimental.macros"
  )
)

lazy val `polynote-config` = project.settings(
  commonSettings,
  libraryDependencies ++= Seq(
    "org.scala-lang" % "scala-reflect" % scalaVersion.value % "provided",
    "org.scala-lang" % "scala-compiler" % scalaVersion.value % "provided"
  ),
  scalacOptions ++= Seq(
    "-language:experimental.macros"
  )
)

lazy val `polynote-runtime` = project.settings(
  commonSettings,
  scalacOptions ++= Seq(
    "-language:experimental.macros"
  ),
  libraryDependencies ++= Seq(
    "black.ninia" % "jep" % "3.9.0",
    "org.scala-lang" % "scala-reflect" % scalaVersion.value % "provided"
  ),
  distFiles := Seq(assembly.value)
).enablePlugins(BuildInfoPlugin)
  .settings(
    buildInfoKeys := Seq[BuildInfoKey](
      name,
      version,
      scalaBinaryVersion,
      BuildInfoKey.action("commit") {
        git.gitHeadCommit.value.getOrElse("unknown")
      },
      BuildInfoKey.action("buildTime") {
        System.currentTimeMillis
      }
    ),
    buildInfoPackage := "polynote.buildinfo"
  ).dependsOn(`polynote-macros`)


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
  distFiles := Seq(assembly.value) ++ (dependencyClasspath in Compile).value.collect {
    case jar if jar.data.name.matches(".*scala-(library|reflect|compiler|collection-compat|xml).*") => jar.data
  },
  coverageExcludedPackages := "polynote\\.kernel\\.interpreter\\.python\\..*;polynote\\.runtime\\.python\\..*" // see https://github.com/scoverage/scalac-scoverage-plugin/issues/176
).dependsOn(`polynote-config`, `polynote-runtime` % "provided", `polynote-runtime` % "test", `polynote-env`)

val `polynote-server` = project.settings(
  commonSettings,
  libraryDependencies ++= Seq(
    "org.scala-lang" % "scala-compiler" % scalaVersion.value % "provided",
    "org.polynote" %% "uzhttp" % "0.2.6",
    "com.vladsch.flexmark" % "flexmark" % "0.34.32",
    "com.vladsch.flexmark" % "flexmark-ext-yaml-front-matter" % "0.34.32",
    "org.slf4j" % "slf4j-simple" % "1.7.25"
  ),
  //unmanagedResourceDirectories in Compile += (ThisBuild / baseDirectory).value / "polynote-frontend" / "dist",
  packageBin := {
    val _ = distUI.value
    (packageBin in Compile).value
  },
  distFiles := Seq(assembly.value)
).dependsOn(`polynote-runtime` % "provided", `polynote-runtime` % "test", `polynote-kernel` % "provided", `polynote-kernel` % "test->test")

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
  ),
  distFiles := Seq(assembly.value)
).dependsOn(`polynote-runtime` % "provided")

lazy val `polynote-spark` = project.settings(
  commonSettings,
  sparkSettings,
  libraryDependencies ++= Seq(
    "org.scala-lang" % "scala-compiler" % scalaVersion.value % "provided"
  ),
  assemblyOption in assembly := {
    (assemblyOption in assembly).value.copy(
      includeScala = false,
      prependShellScript = Some(
        IO.read(file(".") / "scripts/polynote").linesIterator.toSeq
      ))
  },
  distFiles := Seq(assembly.value)
) dependsOn (
  `polynote-kernel` % "provided",
  `polynote-kernel` % "test->test",
  `polynote-server` % "provided",
  `polynote-spark-runtime` % "provided",
  `polynote-spark-runtime` % "test",
  `polynote-runtime` % "provided",
  `polynote-runtime` % "test")

def waitForCommand(command: String): State => State = { st =>
  // there *has* to be a better way to run a command and wait for it to finish...
  var nextState = Command.process(command, st.copy(remainingCommands = Nil))
  while (nextState.remainingCommands.nonEmpty) {
    nextState = Command.process(nextState.remainingCommands.head.commandLine, nextState.copy(remainingCommands = nextState.remainingCommands.tail))
  }
  nextState.copy(remainingCommands = st.remainingCommands)
}

val dist = Command.command(
  "dist",
  "Perform cross-build and build distribution archive",
  "Performs cross-build and builds a distribution archive in target/polynote-dist.tar.gz"
) {
  state =>
    val resultState = waitForCommand("+prepDistFiles")(state)
    val examples = IO.listFiles(file(".") / "docs" / "examples").map(f => (f, s"polynote/examples/${f.getName}"))
    val baseDir = file(".")
    val targetDir = baseDir / "target"
    val tarFile = targetDir / "polynote-dist.tar"
    val outFile = targetDir / "polynote-dist.tar.gz"

    if (tarFile.exists())
      tarFile.delete()

    if(outFile.exists())
      outFile.delete()

    val files = examples ++ List(
      (file(".") / "config-template.yml") -> "polynote/config-template.yml",
      (file(".") / "requirements.txt") -> "polynote/requirements.txt",
      (file(".") / "scripts" / "plugin") -> "polynote/plugin",
      (file(".") / "scripts" / "polynote.py") -> "polynote/polynote.py")

    val distDir = targetDir / "dist"
    val resolvedFiles = files.map {
      case (srcFile, targetPath) => srcFile -> (distDir / targetPath)
    }

    scalaBinaryVersions.foreach {
      binaryVersion => (distDir / "polynote" / "plugins" / binaryVersion).mkdirs()
    }

    IO.copy(resolvedFiles, overwrite = true, preserveLastModified = true, preserveExecutable = true)

    IO.copyDirectory(file(".") / "polynote-frontend" / "dist" / "static", distDir / "polynote" / "static")

    import sys.process.stringSeqToProcess
    println("Making archive")
    Seq("tar", "-cf", tarFile.toString, "-C", distDir.toString, "polynote").!
    Seq("gzip", "-S", ".gz", tarFile.toString).!
    resultState
}

lazy val polynote = project.in(file(".")).aggregate(`polynote-env`, `polynote-runtime`, `polynote-spark-runtime`, `polynote-kernel`, `polynote-server`, `polynote-spark`)
    .settings(
      commonSettings,
      commands ++= Seq(dist)
    )

