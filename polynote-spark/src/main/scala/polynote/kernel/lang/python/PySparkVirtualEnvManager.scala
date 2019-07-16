package polynote.kernel.lang.python

import java.io.File

import cats.effect.IO
import polynote.kernel.{KernelStatusUpdate, TaskInfo}
import polynote.kernel.dependency.{DependencyManager, DependencyManagerFactory}
import polynote.kernel.util.Publish

class PySparkVirtualEnvManager(path: String, taskInfo: TaskInfo, statusUpdates: Publish[IO, KernelStatusUpdate])
  extends VirtualEnvManager(path, taskInfo, statusUpdates) {

  override def mkDependencyProvider(dependencies: List[(String, File)], venv: Option[File]): VirtualEnvDependencyProvider =
    new PySparkVirtualEnvDependencyProvider(dependencies, venv)
}

class PySparkVirtualEnvDependencyProvider(
  override val dependencies: scala.List[(String, File)],
  venv: Option[File]
) extends VirtualEnvDependencyProvider(dependencies, venv) {

  override def beforeInit(path: String): String =
    s"""
       |${super.beforeInit(path)}
       |
       |import os, sys, shutil
       |
       |# first, set environment
       |venvPy = os.path.join(os.environ["VIRTUAL_ENV"], "bin", "python")
       |os.environ["PYSPARK_PYTHON"] = venvPy
       |os.environ["PYSPARK_DRIVER_PYTHON"] = venvPy
       |
       |# sc is the PySpark Context
       |def archive(sc):
       |    print("got spark context", sc, file=sys.stderr)
       |    loc = next(x for x in sys.path if sys.prefix in x and "site-packages" in x)
       |    out_file = shutil.make_archive('deps', 'zip', loc) # make_archive isn't thread safe (https://bugs.python.org/issue30511) but that should be ok here, right?
       |    sc.addPyFile(out_file)
     """.stripMargin

  override def afterInit(path: String): String =
    s"""
      |${super.afterInit(path)}
      |
      |try:
      |   archive(sc)
      |except Exception as e:
      |   print("Unable to load python dependencies to spark!", e, file=sys.stderr)
    """.stripMargin
}

object PySparkVirtualEnvManager {
  object Factory extends DependencyManagerFactory[IO] {
    override def apply(path: String, taskInfo: TaskInfo, statusUpdates: Publish[IO, KernelStatusUpdate]): DependencyManager[IO] = new PySparkVirtualEnvManager(path, taskInfo, statusUpdates)
  }
}
