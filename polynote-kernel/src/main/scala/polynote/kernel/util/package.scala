package polynote.kernel

import zio.RIO

import java.net.URL
import java.nio.file.{Files, Path}
import java.util.function.IntFunction
import zio.blocking.{Blocking, effectBlocking}

package object util {

  def pathOf(cls: Class[_]): URL = cls.getProtectionDomain.getCodeSource.getLocation

  // Since the environment is immutable, use this helper so tests can modify the "env" through system properties.
  def envOrProp(key: String, alternative: String): String = {
    sys.env.getOrElse(key, sys.props.getOrElse(key, alternative))
  }

  /**
    * For collecting results of Files.list into an array
    */
  object NewPathArray extends IntFunction[Array[Path]] {
    override def apply(size: Int): Array[Path] = new Array[Path](size)
  }

  def listFiles(dir: Path): RIO[Blocking, Seq[Path]] = effectBlocking(Files.list(dir)).map {
    paths =>
      val arr = paths.toArray(NewPathArray)
      arr.toSeq
  }

  type RPublish[R, A] = Publish[R, Throwable, A]
  type TPublish[A] = Publish[Any, Throwable, A]
  type UPublish[A] = Publish[Any, Nothing, A]
  type URPublish[R, A] = Publish[R, Nothing, A]
}
