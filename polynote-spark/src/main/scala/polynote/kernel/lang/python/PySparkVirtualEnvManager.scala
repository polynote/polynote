package polynote.kernel.lang.python

import java.io.File

import cats.effect.IO
import polynote.kernel.{KernelStatusUpdate, TaskInfo}
import polynote.kernel.dependency.{DependencyManager, DependencyManagerFactory}
import polynote.kernel.util.Publish

class PySparkVirtualEnvManager(path: String, taskInfo: TaskInfo, statusUpdates: Publish[IO, KernelStatusUpdate])
  extends VirtualEnvManager(path, taskInfo, statusUpdates) {

  override def mkDependencyProvider(venv: File, dependencies: List[(String, File)]): VirtualEnvDependencyProvider =
    new PySparkVirtualEnvDependencyProvider(venv, dependencies)
}

class PySparkVirtualEnvDependencyProvider(
  venv: File,
  override val dependencies: scala.List[(String, File)]
) extends VirtualEnvDependencyProvider(venv, dependencies) {

  override def beforeInit: String =
    s"""
       |${super.beforeInit}
       |
       |import sys, shutil
       |
       |# sc is the PySpark Context
       |def archive(sc):
       |    loc = next(x for x in sys.path if sys.prefix in x and "site-packages" in x)
       |    out_file = "./deps.zip"
       |    shutil.make_archive(out_file, 'zip', loc) # make_archive isn't thread safe (https://bugs.python.org/issue30511) but that should be ok here, right?
       |    sc.addPyFile(out_file)
     """.stripMargin

  override val afterInit: String = "archive(sc)"
}

object PySparkVirtualEnvManager {
  object Factory extends DependencyManagerFactory[IO] {
    override def apply(path: String, taskInfo: TaskInfo, statusUpdates: Publish[IO, KernelStatusUpdate]): DependencyManager[IO] = new PySparkVirtualEnvManager(path, taskInfo, statusUpdates)
  }
}
