package polynote.kernel.util

import java.util.concurrent.ConcurrentHashMap
import scala.collection.JavaConverters._


import cats.syntax.traverse._
import cats.instances.list._
import zio.interop.catz._
import zio.{Semaphore, Task, RIO, UIO, ZIO, Ref}


/**
  * A mutable map of concurrent references.
  */
class RefMap[K, V] private (
  underlying: ConcurrentHashMap[K, Ref[V]],
  semaphore: Semaphore
) {

  def getOrCreate[E](key: K)(create: => RIO[E, V]): RIO[E, V] = synchronized {
    if (underlying.containsKey(key)) {
      ZIO.succeed(underlying.get(key)).flatMap(_.get)
    } else {
      semaphore.withPermit {
        ZIO(underlying.containsKey(key)).flatMap {
          case true => ZIO.succeed(underlying.get(key)).flatMap(_.get)
          case false => create.tap(v => Ref.make[V](v).flatMap(ref => ZIO.effectTotal(Option(underlying.put(key, ref))).unit))
        }
      }
    }
  }

  def get(key: K): UIO[Option[V]] = ZIO.effectTotal(Option(underlying.get(key))).flatMap {
    case Some(ref) => ref.get.map(Some(_))
    case None => ZIO.succeed(None)
  }

  def put(key: K, value: V): UIO[Option[V]] = ZIO.effectTotal(Option(underlying.get(key))).flatMap {
    case Some(ref) => ref.modify(old => old -> value).map(Some(_))
    case None      => semaphore.withPermit {
      Ref.make[V](value).flatMap {
        ref => ZIO.effectTotal(Option(underlying.get(key))).flatMap {
          case Some(ref) => ref.modify(old => old -> value).map(Some(_))
          case None      =>
            ZIO.effectTotal(Option(underlying.put(key, ref))).as(None)
        }
      }
    }
  }

  def remove(key: K): UIO[Option[V]] = ZIO.effectTotal(Option(underlying.remove(key))).flatMap {
    case Some(ref) => ref.get.map(Some(_))
    case None => ZIO.succeed(None)
  }

  def keys: UIO[List[K]] = ZIO.effectTotal(underlying.keys().asScala.toList)

  def values: UIO[List[V]] = underlying.values().asScala.toList.map(_.get).sequence

  def isEmpty: UIO[Boolean] = ZIO.effectTotal(underlying.isEmpty)
}

object RefMap {
  def empty[K, V]: UIO[RefMap[K, V]] = Semaphore.make(1).map {
    semaphore => new RefMap[K, V](new ConcurrentHashMap[K, Ref[V]](), semaphore)
  }

  def of[K, V](initialValues: (K, V)*): ZIO[Any, Throwable, RefMap[K, V]] = Semaphore.make(1).flatMap {
    semaphore => initialValues.toList.map {
      case (k, v) => Ref.make[V](v).map {
        ref => (k, ref)
      }
    }.sequence.map {
      initialRefs => new RefMap[K, V](new ConcurrentHashMap[K, Ref[V]](initialRefs.toMap.asJava), semaphore)
    }
  }
}
