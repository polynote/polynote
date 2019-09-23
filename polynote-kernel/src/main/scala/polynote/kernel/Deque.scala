package polynote.kernel

import java.util.concurrent.ConcurrentLinkedDeque
import scala.collection.JavaConverters._
import cats.syntax.traverse._
import cats.instances.list._
import zio.{Task, ZIO, UIO}
import zio.interop.catz._

/**
  * Just a simple wrapper of [[ConcurrentLinkedDeque]] in a Scala/ZIO API.
  */
final class Deque[A <: AnyRef]() {
  private val inner = new ConcurrentLinkedDeque[A]()

  def add(item: A): UIO[Unit] = ZIO(inner.add(item)).orDie.unit  // ConcurrentLinkedDeque#add will only throw on null element, which would be a critical bug

  def remove(item: A): UIO[Boolean] = ZIO.effectTotal(inner.remove(item))

  def toList: UIO[List[A]] = ZIO.effectTotal(inner.iterator().asScala.toList)

  def foreach(fn: A => Unit): Task[Unit] = ZIO(inner.iterator().asScala.foreach(fn))

  def foreachM[R, E](fn: A => ZIO[R, E, Unit]): ZIO[R, E, Unit] = ZIO(inner.iterator()).orDie.flatMap {
    iter => iter.asScala.foldLeft[ZIO[R, E, Unit]](ZIO.unit) {
      (accum, next) => accum.flatMap {
        _ => fn(next)
      }
    }
  }

  def reverseForeachM[R, E](fn: A => ZIO[R, E, Unit]): ZIO[R, E, Unit] = ZIO(inner.descendingIterator()).orDie.flatMap {
    iter => iter.asScala.foldLeft[ZIO[R, E, Unit]](ZIO.unit) {
      (accum, next) => accum.flatMap {
        _ => fn(next)
      }
    }
  }

  def drainM[R, E](fn: A => ZIO[R, E, Unit]): ZIO[R, E, Unit] = foreachM {
    a => fn(a).ensuring(remove(a))
  }

  def reverseDrainM[R, E](fn: A => ZIO[R, E, Unit]): ZIO[R, E, Unit] = reverseForeachM {
    a => fn(a).ensuring(remove(a))
  }

}