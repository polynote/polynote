package polynote.runtime

import scala.collection.GenTraversable
import scala.util.control.NonFatal

package object util {
  def stringPrefix[A](iterable: GenTraversable[A]): String = iterable.stringPrefix
}
