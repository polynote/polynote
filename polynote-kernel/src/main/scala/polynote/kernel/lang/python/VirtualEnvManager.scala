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
  ): IO[DependencyProvider] = venv.map {
    venv =>

      val deps = dependencies.flatMap(_.get(TinyString("python"))).flatMap(_.toList)

      deps.foreach {
        dep =>
          Seq(s"${venv.getAbsolutePath}/bin/pip", "install", dep).!
      }

      mkDependencyProvider(venv, deps.map(_ -> venv))
  }

  def mkDependencyProvider(venv: File, dependencies: List[(String, File)]) = new VirtualEnvDependencyProvider(venv, dependencies)
}

class VirtualEnvDependencyProvider(venv: File, val dependencies: List[(String, File)]) extends DependencyProvider {

  private val path = venv.getAbsolutePath

  // call this on Jep initialization to set the venv properly
  def beforeInit: String =
    s"""exec(open("$path/bin/activate_this.py").read(), {'__file__': "$path/bin/activate_this.py"}) """

  // call this after interpreter initialization is complete
  def afterInit: String = ""
}

object VirtualEnvManager {
  object Factory extends DependencyManagerFactory[IO] {
    override def apply(path: String, taskInfo: TaskInfo, statusUpdates: Publish[IO, KernelStatusUpdate]): DependencyManager[IO] = new VirtualEnvManager(path, taskInfo, statusUpdates)
  }
}