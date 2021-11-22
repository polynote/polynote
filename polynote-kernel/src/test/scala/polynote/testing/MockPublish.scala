package polynote.testing

import polynote.kernel.util.Publish
import zio.{UIO, ZIO}

import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.JavaConverters._

class MockPublish[A]() extends Publish[Any, Nothing, A] {
  private val published = new ConcurrentLinkedQueue[A]()
  @volatile private var publishCount = 0

  def publish(a: A): UIO[Unit] = {
    publishCount += 1
    ZIO.effectTotal {
      published.add(a)
    }.unit
  }
  def reset(): Unit = {
    published.clear()
    publishCount = 0
  }
  def toList: UIO[List[A]] = ZIO.flatten {
    ZIO.effectTotal {
      if (published.size() < publishCount) {
        // waiting on something to publish asynchronously
        toList
      } else ZIO.succeed(published.iterator().asScala.toList)
    }
  }
}
