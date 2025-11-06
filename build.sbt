import java.io.File
import scala.util.Try

name := "polynote"

val buildUI: TaskKey[Unit] = taskKey[Unit]("Building UI...")
val distUI: TaskKey[Unit] = taskKey[Unit]("Building UI for distribution...")
val runAssembly: TaskKey[Unit] = taskKey[Unit]("Running spark server from assembly...")
val distFiles: TaskKey[Seq[File]] = taskKey[Seq[File]]("Distribution files")
val prepDistFiles: TaskKey[Seq[File]] = taskKey[Seq[File]]("Prepare distribution files")
val dependencyJars: TaskKey[Seq[(File, String)]] = taskKey("Dependency JARs which aren't included in the assembly")
val polynoteJars: TaskKey[Seq[(File, String)]] = taskKey("Polynote JARs")
val sparkVersion: SettingKey[String] = settingKey("Spark version")
val circeVersion: SettingKey[String] = settingKey("circe version")
val circeYamlVersion: SettingKey[String] = settingKey("circe-yaml version")
val sparkInstallLocation: SettingKey[String] = settingKey("Location of Spark installation(s)")
val sparkHome: SettingKey[String] = settingKey("Location of specific Spark installation to use for SPARK_HOME during tests")


val versions = new {
  val coursier   = "2.0.0-RC5-6"
  val zio        = "1.0.11"
  val javaparser = "3.25.5"
}


lazy val nativeLibraryPath = {
  val jepPath = sys.env.get("JEP_DIR") orElse Try {
    import sys.process._
    Seq("bash", "-c", "pip3 show jep |grep ^Location |cut -d' ' -f2")
      .lineStream.toList
      .headOption.map(_.trim)
      .filterNot(_.isEmpty)
      .map(_ + "/jep")
  }.toOption.flatten orElse
    sys.env.get("JAVA_LIBRARY_PATH") orElse
    sys.env.get("LD_LIBRARY_PATH") orElse
    sys.env.get("DYLD_LIBRARY_PATH") getOrElse "."

  Seq(jepPath, ".").mkString(File.pathSeparator)
}

val distBuildDir = file(".") / "target" / "dist" / "polynote"
val scalaVersions = Seq("2.12.20", "2.13.11")
lazy val scalaBinaryVersions = scalaVersions.map {
  ver => ver.split('.').take(2).mkString(".")
}.distinct

val shapelessVersion = Map("2.12" -> "2.3.2", "2.13" -> "2.3.3")

val commonSettings = Seq(
  scalaVersion := "2.12.20",
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
  version := "0.7.0-SNAPSHOT",
  dependencyOverrides += "com.chuusai" %% "shapeless" % shapelessVersion(scalaBinaryVersion.value),
  publishTo := sonatypePublishToBundle.value,
  // disable scalaDoc generation because it's causing weird compiler errors and we don't use it anyways
  Compile / packageDoc / publishArtifact := false,
  developers := List(
    Developer(id = "jeremyrsmith", name = "Jeremy Smith", email = "", url = url("https://github.com/jeremyrsmith")),
    Developer(id = "jonathanindig", name = "Jonathan Indig", email = "", url = url("https://github.com/jonathanindig")),
    Developer(id = "omidmogasemi", name = "Omid Mogasemi", email = "", url = url("https://github.com/omidmogasemi"))
  ),
  javacOptions ++= Seq("-source", "8", "-target", "8"),
  scalacOptions ++= Seq(
    "-language:higherKinds",
    "-unchecked",
    "-target:jvm-1.8",
  ) ++ (
    if (scalaBinaryVersion.value.startsWith("2.13")) Nil else Seq("-Ypartial-unification")
  ) ++ (
    if (sys.props.get("java.version").exists(_.startsWith("1.8"))) Nil else Seq("-release", "8")
  ),
  Test / fork := true,
  Test / javaOptions += s"-Djava.library.path=$nativeLibraryPath",
  libraryDependencies ++= Seq(
    "org.scalatest" %% "scalatest" % "3.0.8" % "test",
    "org.scalacheck" %% "scalacheck" % "1.14.0" % "test"
  ),
  assembly / assemblyMergeStrategy := {
    case PathList("META-INF", "CHANGES") => MergeStrategy.discard
    case PathList("coursier", "shaded", xs @ _*) => MergeStrategy.first // coursier shades some of the same classes. assembly somehow can't dedupe even though they seem identical to me.
    case PathList(_, "BuildInfo$.class") => MergeStrategy.discard
    case x if x.endsWith("module-info.class") => MergeStrategy.discard
    case x =>
      val oldStrategy = (assembly / assemblyMergeStrategy).value
      oldStrategy(x)
  },
  assembly / assemblyOption := {
    (assembly / assemblyOption).value.withIncludeScala(false)
  },
  assembly / assemblyShadeRules := Seq(ShadeRule.rename("shapeless.**" -> "polynote.shaded.shapeless.@1", "com.google.**" -> "polynote.shaded.google.@1").inAll),
  Global / cancelable := true,
  addCompilerPlugin("org.typelevel" % "kind-projector" % "0.13.3" cross CrossVersion.full),
  buildUI := {
    sys.process.Process(Seq("npm", "run", "build"), file("./polynote-frontend/")) ! streams.value.log
  },
  distUI := {
    sys.process.Process(Seq("npm", "run", "clean"), file("./polynote-frontend/")) ! streams.value.log
    sys.process.Process(Seq("npm", "run", "dist"), file("./polynote-frontend/")) ! streams.value.log
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
  assembly / test := {},
  circeVersion := "0.14.3",
  circeYamlVersion := "0.15.2"
)

lazy val `polynote-macros` = project.settings(
  commonSettings,
  libraryDependencies ++= Seq("org.scala-lang" % "scala-reflect" % scalaVersion.value % "provided"),
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
    "black.ninia" % "jep" % "4.2.1",
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
    "org.scalameta" % "semanticdb-scalac-core" % "4.9.9" cross CrossVersion.patch,
    "dev.zio" %% "zio" % versions.zio,
    "dev.zio" %% "zio-streams" % versions.zio,
    "org.scodec" %% "scodec-core" % "1.11.4",
    "io.get-coursier" %% "coursier" % versions.coursier,
    "io.get-coursier" %% "coursier-cache" % versions.coursier,
    "io.github.classgraph" % "classgraph" % "4.8.47",
    "org.scala-lang.modules" %% "scala-collection-compat" % "2.1.1",
    "org.scala-lang.modules" %% "scala-java8-compat" % "0.9.0",
    "io.circe" %% "circe-yaml" % circeYamlVersion.value,
    "io.circe" %% "circe-generic" % circeVersion.value,
    "io.circe" %% "circe-generic-extras" % circeVersion.value,
    "io.circe" %% "circe-parser" % circeVersion.value,
    "net.sf.py4j" % "py4j" % "0.10.7",
    "com.github.javaparser" % "javaparser-core" % versions.javaparser,
    "com.github.javaparser" % "javaparser-symbol-solver-core" % versions.javaparser,
    "org.scalamock" %% "scalamock" % "4.4.0" % "test"
  ),
  distFiles := Seq(assembly.value) ++ (Compile / dependencyClasspath).value.collect {
    case jar if jar.data.name.matches(".*scala-(library|reflect|compiler|collection-compat|xml).*") => jar.data
  },
  coverageExcludedPackages := "polynote\\.kernel\\.interpreter\\.python\\..*;polynote\\.runtime\\.python\\..*" // see https://github.com/scoverage/scalac-scoverage-plugin/issues/176
).dependsOn(`polynote-runtime` % "provided", `polynote-runtime` % "test", `polynote-env`)

val `polynote-server` = project.settings(
  commonSettings,
  libraryDependencies ++= Seq(
    "org.scala-lang" % "scala-compiler" % scalaVersion.value % "provided",
    "org.polynote" %% "uzhttp" % "0.2.8",
    "com.vladsch.flexmark" % "flexmark" % "0.34.32",
    "com.vladsch.flexmark" % "flexmark-ext-yaml-front-matter" % "0.34.32",
    "org.slf4j" % "slf4j-simple" % "1.7.25"
  ),
  //Compile / unmanagedResourceDirectories += (ThisBuild / baseDirectory).value / "polynote-frontend" / "dist",
  packageBin := {
    val _ = distUI.value
    (Compile / packageBin).value
  },
  distFiles := Seq(assembly.value),
  Test / testOptions += Tests.Argument("-oF")
).dependsOn(`polynote-runtime` % "provided", `polynote-runtime` % "test", `polynote-kernel` % "provided", `polynote-kernel` % "test->test")

// Supported Spark versions for each Scala binary version
// The default version (used if SPARK_VERSION env var is not set) is the last one in each list
val sparkVersions = Map(
  "2.12" -> Seq("3.3.4", "3.5.7"),
  "2.13" -> Seq("3.3.4", "3.5.7")
)

// keep expected checksums here. This has two benefits over checking the sha512sum from the archive:
// 1. We'll know if anything changes in the archive
// 2. Spark's checksums are generated with gpg rather than sha512sum up until a certain version, so they're a pain to verify
//    See https://issues.apache.org/jira/browse/SPARK-30683
// To add to this list, download the tarball for the new version from the apache repo and run `sha512sum <file>.tgz`
// Map key format: "package-name.tgz" -> checksum
val sparkChecksums = Map(
  "spark-3.3.4-bin-hadoop3.tgz" -> "a3874e340a113e95898edfa145518648700f799ffe2d1ce5dde7743e88fdf5559d79d9bcb1698fdfa5296a63c1d0fc4c8e32a93529ed58cd5dcf0721502a1fc7",
  "spark-3.3.4-bin-hadoop3-scala2.13.tgz" -> "0662a59544e9c9c74f32bce9a4c80f408a4b86b183ccc7ec4b2a232d524e534931f5537b18376304db6d7d54d290aa415431abbd8ec2d1ebc256dcc5cc5802d7",
  "spark-3.5.7-bin-hadoop3.tgz" -> "f3b7d5974d746b9aaecb19104473da91068b698a4d292177deb75deb83ef9dc7eb77062446940561ac9ab7ee3336fb421332b1c877292dab4ac1b6ca30f4f2e0",
  "spark-3.5.7-bin-hadoop3-scala2.13.tgz" -> "b1f3812bee0f27be0cea588f7da82a9e69633bb69f7b8abbdf66b9196dedc2508824bbe8233cc1e47a047041afbd2ec42a1692fba12e4d8c601daf829760d11e",
)

// Downloading from https://archive.apache.org/dist/spark is very slow, so we download the packages manually then upload to GitHub Releases
// to work around the issue.
// For new spark versions, add the package via https://github.com/polynote/polynote/releases/edit/0.6.1
val sparkDistUrl: String => String =
  ver => s"https://github.com/polynote/polynote/releases/download/0.6.1"

val sparkSettings = Seq(
  resolvers ++= {
    Seq(MavenRepository(name = "Apache Staging", root = "https://repository.apache.org/content/repositories/staging"))
  },
  sparkVersion := {
    val versions = sparkVersions(scalaBinaryVersion.value)
    sys.env.get("SPARK_VERSION")
      .filter(versions.contains)
      .getOrElse(versions.last)
  },
  Compile / unmanagedSourceDirectories += {
    val sparkMajorMinor = sparkVersion.value.split("\\.").take(2).mkString(".")
    (LocalRootProject / baseDirectory).value / "polynote-spark-runtime" / "src" / "main" / s"spark_$sparkMajorMinor" / "scala"
  },
  libraryDependencies ++= Seq(
    "org.apache.spark" %% "spark-sql" % sparkVersion.value % "provided",
    "org.apache.spark" %% "spark-repl" % sparkVersion.value % "provided",
    "org.apache.spark" %% "spark-sql" % sparkVersion.value % "test",
    "org.apache.spark" %% "spark-repl" % sparkVersion.value % "test"
  ),
  sparkInstallLocation := {
    sys.env.get("SPARK_INSTALL_LOCATION")
      .getOrElse((file(".").getAbsoluteFile / "target" / "spark").getCanonicalPath)
  },
  sparkHome := {
    val pkgName = if (scalaBinaryVersion.value == "2.13") s"spark-${sparkVersion.value}-bin-hadoop3-scala2.13" else s"spark-${sparkVersion.value}-bin-hadoop3"
    (file(sparkInstallLocation.value) / pkgName).toString
  },
  Test / testOptions += Tests.Setup { () =>
    import sys.process._
    val baseDir = file(sparkInstallLocation.value)
    val distVersion = sparkVersion.value

    val pkgName = if (scalaBinaryVersion.value == "2.13") s"spark-$distVersion-bin-hadoop3-scala2.13" else s"spark-$distVersion-bin-hadoop3"
    val filename = s"$pkgName.tgz"
    val distUrl = url(s"${sparkDistUrl(distVersion)}/$filename")
    val destDir = baseDir / pkgName

    // If the Spark distribution is already extracted, skip the entire setup
    if (destDir.exists()) {
      println(s"$destDir already exists, test setup completed")
    } else {
      // Use a lockfile to coordinate concurrent test setups
      baseDir.mkdirs()
      val lockFile = baseDir / s"spark_${distVersion}_scala_${scalaBinaryVersion.value}_test_setup_is_running.lock"

      // Try to acquire the lock with atomic file creation
      var lockAcquired = false
      try {
        lockAcquired = lockFile.createNewFile()
      } catch {
        case _: Exception => lockAcquired = false
      }

      if (!lockAcquired) {
        // Another process holds the lock, wait for it to finish
        println(s"Lock file $lockFile exists, test setup is already running. Waiting for it to finish...")
        val start = System.currentTimeMillis()
        val timeout = 10 * 60 * 1000 // 10 minutes
        val checkInterval = 5 * 1000 // 5 seconds

        while (System.currentTimeMillis() < start + timeout && lockFile.exists() && !destDir.exists()) {
          val elapsed = (System.currentTimeMillis() - start) / 1000
          println(s"Waiting for test setup to complete... ${elapsed}s elapsed")
          Thread.sleep(checkInterval)
        }

        // Check if setup completed successfully
        if (destDir.exists()) {
          println("Test setup completed by another process")
        } else if (lockFile.exists()) {
          // Lock file still exists but destDir wasn't created - stale lock file
          println(s"Lock file appears stale (no progress after ${timeout}ms). Removing lock and retrying...")
          lockFile.delete()
          // Give it one more chance - if destDir still doesn't exist, the other process may have failed
          Thread.sleep(1000)
          if (!destDir.exists()) {
            throw new Exception(s"Test setup did not complete after waiting ${timeout}ms. Please check for errors and try again.")
          }
        }
      } else {
        // We acquired the lock, perform the setup
        try {
          // Double-check destDir in case another process created it while we were acquiring the lock
          if (destDir.exists()) {
            println(s"$destDir already exists, skipping download and extract")
          } else {
            val pkgFile = baseDir / filename
            if (!pkgFile.exists()) {
              pkgFile.createNewFile()
              println(s"Downloading $distUrl to $pkgFile...")
              (distUrl #> pkgFile).!!
            }

            println(s"Verifying checksum for $pkgFile for $distVersion...")
            val expectedChecksum = sparkChecksums.getOrElse(filename,
              throw new Exception(s"No checksum found for $filename. Please add it to sparkChecksums in build.sbt"))
            val actualChecksum = Seq("sha512sum", pkgFile.toString).!!.trim.split(" ").head
            if (actualChecksum == expectedChecksum) {
              println(s"Checksum verified for $pkgFile for $distVersion")
            } else {
              throw new Exception(s"Checksum mismatch for $pkgFile for $distVersion. Expected:\n$expectedChecksum\nGot:\n$actualChecksum")
            }

            println(s"Extracting $pkgFile to $baseDir")
            println(Seq("tar", "-zxpf", pkgFile.toString, "-C", baseDir.toString).!!)
            println(s"Successfully extracted Spark to $destDir")
          }
        } finally {
          // Always clean up the lock file
          if (lockFile.exists()) {
            lockFile.delete()
            println(s"Released lock file $lockFile")
          }
        }
      }
    }

    println("Test setup completed")
  },
  Test / envVars ++= {
    Map(
      "SPARK_HOME" -> sparkHome.value,
      "PATH" -> Seq(sparkHome, sys.env("PATH")).mkString(File.pathSeparator)
    )
  },
  Test / javaOptions ++= Seq(
    // Add JVM flags required for Spark to work with Java 17+
    // These open internal Java modules that Spark's unsafe code needs to access.
    // Flags are based on those used by Spark:
    // https://github.com/apache/spark/blob/v3.3.0/launcher/src/main/java/org/apache/spark/launcher/JavaModuleOptions.java#L28
    "-XX:+IgnoreUnrecognizedVMOptions",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
    "--add-opens=java.base/java.io=ALL-UNNAMED",
    "--add-opens=java.base/java.net=ALL-UNNAMED",
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED",
    "--add-opens=java.base/sun.security.action=ALL-UNNAMED",
    "--add-opens=java.base/sun.util.calendar=ALL-UNNAMED",
    "--add-opens=java.security.jgss/sun.security.krb5=ALL-UNNAMED"
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
  Test / testOptions += Tests.Argument("-oF"),
  libraryDependencies ++= Seq(
    "org.scala-lang" % "scala-compiler" % scalaVersion.value % "provided"
  ),
  assembly / assemblyOption := {
    (assembly / assemblyOption).value
      .withIncludeScala(false)
      .withPrependShellScript(Some(
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
      binaryVersion =>
        (distDir / "polynote" / "plugins" / binaryVersion).mkdirs()
        (distDir / "polynote" / "plugins.d" / binaryVersion).mkdirs()
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

