package polynote.testing

import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.JavaConverters._

import fs2.Pipe
import polynote.kernel.KernelStatusUpdate
import polynote.kernel.util.Publish
import zio.{Task, ZIO}

class MockPublish[A]() extends Publish[Task, A] {
  private val published = new ConcurrentLinkedQueue[A]()
  @volatile private var publishCount = 0

  def publish1(a: A): Task[Unit] = {
    publishCount += 1
    ZIO {
      published.add(a)
    }.unit
  }
  override def publish: Pipe[Task, A, Unit] = _.evalMap(publish1)
  def reset(): Unit = {
    published.clear()
    publishCount = 0
  }
  def toList: Task[List[A]] = ZIO.flatten {
    ZIO {
      if (published.size() < publishCount) {
        // waiting on something to publish asynchronously
        toList
      } else ZIO.succeed(published.iterator().asScala.toList)
    }
  }
}
