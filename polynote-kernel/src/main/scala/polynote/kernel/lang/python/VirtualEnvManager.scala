package polynote.kernel.lang.python

import java.io.File

import cats.effect.IO
import polynote.config
import polynote.config.pip
import polynote.kernel.dependency.{DependencyManager, DependencyManagerFactory, DependencyProvider}
import polynote.kernel.util.Publish
import polynote.kernel.{KernelStatusUpdate, TaskInfo, TaskStatus, UpdatedTasks}
import polynote.messages.TinyString

import scala.sys.process._


class VirtualEnvManager(val path: String, val taskInfo: TaskInfo, val statusUpdates: Publish[IO, KernelStatusUpdate]) extends DependencyManager[IO] {

  lazy val venv = IO {

    val venvFile = new File(path).toPath.resolve("venv").toFile

    if (!venvFile.exists()) {
      // I added the `--system-site-packages` flag so that we can rely on system packages in the majority of cases where
      // users don't need a specific version. That way, e.g., it won't take many minutes to compile numpy every time
      // the kernel starts up...
      Seq("virtualenv", "--system-site-packages", "--python=python3", venvFile.toString).!
    }

    venvFile
  }

  override def getDependencyProvider(
    repositories: List[config.RepositoryConfig],
    dependencies: List[String],
    exclusions: List[String]
  ): IO[DependencyProvider] = {

    if (dependencies.nonEmpty) {
      venv.map {
        venv =>

          val venvTask = TaskInfo("Creating VirtualEnv", "", "", TaskStatus.Running, 0.toByte)
          statusUpdates.publish1(UpdatedTasks(List(venvTask)))

          def pip(action: String, dep: String, extraOptions: List[String] = Nil): List[String] = {
            val baseCmd = List(s"${venv.getAbsolutePath}/bin/pip", action)

            val options: List[String] = repositories.collect {
              case pip(url) => Seq("--extra-index-url", url)
            }.flatten ::: extraOptions

            baseCmd ::: options ::: dep :: Nil
          }

          dependencies.zipWithIndex.foreach {
            case (dep, idx) =>
              statusUpdates.publish1(UpdatedTasks(List(
                venvTask.copy(progress = ((idx / (dependencies.length + 1)) * 255).toByte),
                taskInfo.copy(progress = ((idx / (dependencies.length + 1)) * 255).toByte)
              )))
              pip("install", dep).!
              pip("download", dep, List("--dest", s"${venv.getAbsolutePath}/deps/")).!
          }

          statusUpdates.publish1(UpdatedTasks(List(venvTask.copy(status =  TaskStatus.Complete, progress = 255.toByte))))

          // TODO: actual dep locations?
          mkDependencyProvider(dependencies.map(_ -> venv), Option(venv))
      }
    } else {
      IO.pure(mkDependencyProvider(Nil, None))
    }
  }

  def mkDependencyProvider(dependencies: List[(String, File)], venv: Option[File]) = new VirtualEnvDependencyProvider(dependencies, venv)
}

class VirtualEnvDependencyProvider(val dependencies: List[(String, File)], venv: Option[File]) extends DependencyProvider {

  final val venvPath: Option[String] = venv.map(_.getAbsolutePath)

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