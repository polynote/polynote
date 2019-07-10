package polynote.kernel.lang.python

import java.io.File

import sys.process._
import cats.effect.{ContextShift, IO}
import jep.Jep
import polynote.kernel.util.Memoize

// TODO:
//    Pip dependencies need to be threaded all the way through from the UI to the interpreter.
//    Right now all 'Dependencies' are presumed to be Scala, so we should probably fix that
//      Maybe dependencies should be keyed by their interpreter, and there should be some way for interpreters to
//      register some config that'll get to the UI. Maybe some message that gets sent (like interpreters in the handshake)

case class PipDependency(name: String, version: Option[String]) {
  def toPipDepString: String = version.map(v => s"$name==$v").getOrElse(name)
}

object PipDependency {
  def apply(pipDepString: String): PipDependency = {
    pipDepString.split("==") match {
      case Array(name, version) =>
        PipDependency(name, Option(version))
      case Array(name) =>
        PipDependency(name, None)
      case _ => throw new Exception(s"Unable to parse pip dependency $pipDepString")
    }
  }
}

/**
  * Class handling interaction with the virtualenv.
  *
  * @param pathPrefix where to put the virtualenv. Generally the notebook name.
  * @param dependencies pip dependencies to install in the virtualenv.
  */
class VirtualEnvManager(pathPrefix: String, dependencies: Seq[PipDependency])(implicit contextShift: ContextShift[IO]) {

  lazy val venv: Memoize[File] = Memoize.unsafe(IO {

    // I added the `--system-site-packages` flag so that we can rely on system packages in the majority of cases where
    // users don't need a specific version. That way, e.g., it won't take many minutes to compile numpy every time
    // the kernel starts up...
    Seq("virtualenv", "--system-site-packages", "--python=python3", pathPrefix).!

    dependencies.foreach {
      dep =>
        Seq(s"$pathPrefix/bin/pip", "install", dep.toPipDepString).!
    }

    new File(pathPrefix)
  })

  // call this on Jep initialization to set the venv properly
  lazy val activate: IO[String] = venv.get.map {
    path =>
      s"""exec(open("${path.getAbsolutePath}/bin/activate_this.py").read(), {'__file__': "${path.getAbsolutePath}/bin/activate_this.py"}) """
  }

  /**
    * Defines a command to be used to add dependencies to Spark.
    *
    * TODO: maybe this should go somewhere else?
    */
  val pyDepCommand: String =
    """
      |import sys, shutil
      |
      |# sc is the PySpark Context
      |def archive(sc):
      |    loc = next(x for x in sys.path if sys.prefix in x and "site-packages" in x)
      |    out_file = "./deps.zip"
      |    shutil.make_archive(out_file, 'zip', loc) # make_archive isn't thread safe (https://bugs.python.org/issue30511) but that should be ok here, right?
      |    sc.addPyFile(out_file)
      |""".stripMargin
}
