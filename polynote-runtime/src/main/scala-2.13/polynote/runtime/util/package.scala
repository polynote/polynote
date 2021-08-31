package polynote.runtime

import scala.util.control.NonFatal

package object util {
  def stringPrefix[A](iterable: Iterable[A]): String = {
    // stringPrefix became protected in Scala 2.13
    try iterable.getClass.getMethod("collectionClassName").invoke(iterable).asInstanceOf[String] catch {
      case NonFatal(_) => iterable.getClass.getSimpleName
    }
  }
}
