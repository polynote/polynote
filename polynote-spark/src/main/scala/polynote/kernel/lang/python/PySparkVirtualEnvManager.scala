package polynote.kernel.lang.python

import java.io.File

import cats.effect.IO
import polynote.kernel.{KernelStatusUpdate, TaskInfo}
import polynote.kernel.dependency.{DependencyManager, DependencyManagerFactory}
import polynote.kernel.util.Publish

class PySparkVirtualEnvManager(path: String, taskInfo: TaskInfo, statusUpdates: Publish[IO, KernelStatusUpdate])
  extends VirtualEnvManager(path, taskInfo, statusUpdates) {

  override def mkDependencyProvider(dependencies: List[(String, File)], venv: File): VirtualEnvDependencyProvider =
    new PySparkVirtualEnvDependencyProvider(dependencies, venv)
}

class PySparkVirtualEnvDependencyProvider(
  override val dependencies: scala.List[(String, File)],
  venv: File
) extends VirtualEnvDependencyProvider(dependencies, venv) {

  override def beforeInit(path: String): String =
    s"""
       |${super.beforeInit(path)}
       |
       |import os
       |
       |# set driver python before setting up pyspark
       |venvPy = os.path.join(os.environ.get("VIRTUAL_ENV", "$path"), "bin", "python")
       |os.environ["PYSPARK_DRIVER_PYTHON"] = venvPy
     """.stripMargin

  override def afterInit(path: String): String =
    s"""
      |${super.afterInit(path)}
      |
      |from pathlib import Path
      |import shutil
      |
      |# archive venv and send to Spark cluster
      |for dep in Path('$path', 'deps').resolve().glob('*.whl'):
      |    # we need to rename the wheels to zips because that's what spark wants... sigh
      |    as_zip = dep.with_suffix('.zip')
      |    if not as_zip.exists():
      |        shutil.copy(dep, as_zip)
      |    sc.addPyFile(str(as_zip))
      |
    """.stripMargin
}

object PySparkVirtualEnvManager {
  object Factory extends DependencyManagerFactory[IO] {
    override def apply(path: String, taskInfo: TaskInfo, statusUpdates: Publish[IO, KernelStatusUpdate]): DependencyManager[IO] = new PySparkVirtualEnvManager(path, taskInfo, statusUpdates)
  }
}
