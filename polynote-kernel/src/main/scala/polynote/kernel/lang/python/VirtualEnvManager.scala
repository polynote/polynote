package polynote.kernel.lang.python

import java.io.File

import cats.effect.IO
import polynote.config
import polynote.config.DependencyConfigs
import polynote.kernel.dependency.{DependencyManager, DependencyManagerFactory, DependencyProvider}
import polynote.kernel.util.Publish
import polynote.kernel.{KernelStatusUpdate, TaskInfo}
import polynote.messages.TinyString

import scala.sys.process._

// TODO:
//    Pip dependencies need to be threaded all the way through from the UI to the interpreter.
//    Right now all 'Dependencies' are presumed to be Scala, so we should probably fix that
//      Maybe dependencies should be keyed by their interpreter, and there should be some way for interpreters to
//      register some config that'll get to the UI. Maybe some message that gets sent (like interpreters in the handshake)

class VirtualEnvManager(val path: String, val taskInfo: TaskInfo, val statusUpdates: Publish[IO, KernelStatusUpdate]) extends DependencyManager[IO] {

  lazy val venv = IO {

    // I added the `--system-site-packages` flag so that we can rely on system packages in the majority of cases where
    // users don't need a specific version. That way, e.g., it won't take many minutes to compile numpy every time
    // the kernel starts up...
    Seq("virtualenv", "--system-site-packages", "--python=python3", path).!

    new File(path)
  }

  override def getDependencyProvider(
    repositories: List[config.RepositoryConfig],
    dependencies: List[DependencyConfigs],
    exclusions: List[String]
  ): IO[DependencyProvider] = {

    val deps = dependencies.flatMap(_.get(TinyString("python"))).flatMap(_.toList)

    if (deps.nonEmpty) {
      venv.map {
        venv =>

          deps.foreach {
            dep =>
              Seq(s"${venv.getAbsolutePath}/bin/pip", "install", dep).!
          }

          // TODO: actual dep locations?
          mkDependencyProvider(deps.map(_ -> venv), Option(venv))
      }
    } else {
      IO.pure(mkDependencyProvider(Nil, None))
    }
  }

  def mkDependencyProvider(dependencies: List[(String, File)], venv: Option[File]) = new VirtualEnvDependencyProvider(dependencies, venv)
}

class VirtualEnvDependencyProvider(val dependencies: List[(String, File)], venv: Option[File]) extends DependencyProvider {

  protected val venvPath: Option[String] = venv.map(_.getAbsolutePath)

  // call this on Jep initialization to set the venv properly
  protected def beforeInit(path: String): String = s"""exec(open("$path/bin/activate_this.py").read(), {'__file__': "$path/bin/activate_this.py"}) """
  final def runBeforeInit: String = venvPath.map(beforeInit).getOrElse("")

  // call this after interpreter initialization is complete
  protected def afterInit(path: String): String = ""
  final def runAfterInit: String = venvPath.map(afterInit).getOrElse("")
}

object VirtualEnvManager {
  object Factory extends DependencyManagerFactory[IO] {
    override def apply(path: String, taskInfo: TaskInfo, statusUpdates: Publish[IO, KernelStatusUpdate]): DependencyManager[IO] = new VirtualEnvManager(path, taskInfo, statusUpdates)
  }
}