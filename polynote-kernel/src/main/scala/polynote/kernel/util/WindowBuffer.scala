package polynote.kernel.util

import java.util.concurrent.ConcurrentLinkedDeque

import scala.collection.JavaConverters._

/**
  * A buffer which can accumulate up to `size` values. Once this limit is reached, the least-recently-added values will
  * start being discarded in the order they were added in order to keep the number of values at this limit.
  *
  * @param size     The number of elements to allow
  * @param elements The initial elements to populate into the buffer
  */
class WindowBuffer[T](size: Int) {
  private val underlying = new ConcurrentLinkedDeque[T]()

  def add(value: T): Unit = {
    underlying.addLast(value)

    if (underlying.size() > size) {
      underlying.synchronized {
        if (underlying.size() > size) {
          underlying.removeFirst()
        }
      }
    }
  }

  def toList: List[T] = underlying.iterator().asScala.toList

  def clear(): Unit = underlying.clear()
}
