package polynote.kernel.util

import java.util.concurrent.ConcurrentHashMap
import scala.collection.JavaConverters._

import cats.effect.concurrent.Ref
import cats.syntax.traverse._
import cats.instances.list._
import zio.interop.catz._
import zio.{Semaphore, Task, TaskR, UIO, ZIO}


/**
  * A mutable map of concurrent references.
  */
class RefMap[K, V] private (
  underlying: ConcurrentHashMap[K, Ref[Task, V]],
  semaphore: Semaphore
) {

  def getOrCreate[E](key: K)(create: => TaskR[E, V]): TaskR[E, V] = synchronized {
    if (underlying.containsKey(key)) {
      ZIO.succeed(underlying.get(key)).flatMap(_.get)
    } else {
      semaphore.withPermit {
        if (underlying.containsKey(key)) {
          ZIO.succeed(underlying.get(key)).flatMap(_.get)
        } else {
          create.tap(v => Ref[Task].of(v).flatMap(ref =>
            ZIO(underlying.put(key, ref))))
        }
      }
    }
  }

  def getOrCreateRef[E](key: K)(create: TaskR[E, Ref[Task, V]]): TaskR[E, V] = synchronized {
    if (underlying.containsKey(key)) {
      ZIO.succeed(underlying.get(key)).flatMap(_.get)
    } else {
      semaphore.withPermit {
        if (underlying.containsKey(key)) {
          ZIO.succeed(underlying.get(key)).flatMap(_.get)
        } else {
          create.tap(ref => ZIO(underlying.put(key, ref))).flatMap(_.get)
        }
      }
    }
  }

  def get(key: K): Task[Option[V]] = ZIO.effectTotal(Option(underlying.get(key))).flatMap {
    case Some(ref) => ref.get.map(Some(_))
    case None => ZIO.succeed(None)
  }

  def remove(key: K): Task[Option[V]] = ZIO.effectTotal(Option(underlying.remove(key))).flatMap {
    case Some(ref) => ref.get.map(Some(_))
    case None => ZIO.succeed(None)
  }

  def keys: Task[List[K]] = ZIO(underlying.keys().asScala.toList)

  def values: Task[List[V]] = underlying.values().asScala.toList.map(_.get).sequence

}

object RefMap {
  def empty[K, V]: UIO[RefMap[K, V]] = Semaphore.make(1).map {
    semaphore => new RefMap[K, V](new ConcurrentHashMap[K, Ref[Task, V]](), semaphore)
  }

  def of[K, V](initialValues: (K, V)*): ZIO[Any, Throwable, RefMap[K, V]] = Semaphore.make(1).flatMap {
    semaphore => initialValues.toList.map {
      case (k, v) => Ref.of[Task, V](v).map {
        ref => (k, ref)
      }
    }.sequence.map {
      initialRefs => new RefMap[K, V](new ConcurrentHashMap[K, Ref[Task, V]](initialRefs.toMap.asJava), semaphore)
    }
  }
}
