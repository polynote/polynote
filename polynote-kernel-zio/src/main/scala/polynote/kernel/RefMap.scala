package polynote.kernel

import java.util.concurrent.ConcurrentHashMap

import cats.effect.concurrent.Ref
import zio.{Semaphore, Task, TaskR, UIO, ZIO}
import zio.interop.catz._

class RefMap[K, V] private (
  underlying: ConcurrentHashMap[K, Ref[Task, V]],
  semaphore: Semaphore
) {

  def getOrCreate[E](key: K)(create: TaskR[E, V]): TaskR[E, V] = synchronized {
    if (underlying.contains(key)) {
      ZIO.succeed(underlying.get(key)).flatMap(_.get)
    } else {
      semaphore.withPermit {
        if (underlying.contains(key)) {
          ZIO.succeed(underlying.get(key)).flatMap(_.get)
        } else {
          create.tap(v => Ref[Task].of(v).flatMap(ref => ZIO(underlying.put(key, ref))))
        }
      }
    }
  }

  def getOrCreateRef[E](key: K)(create: TaskR[E, Ref[Task, V]]): TaskR[E, V] = synchronized {
    if (underlying.contains(key)) {
      ZIO.succeed(underlying.get(key)).flatMap(_.get)
    } else {
      semaphore.withPermit {
        if (underlying.contains(key)) {
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

}

object RefMap {
  def empty[K, V]: UIO[RefMap[K, V]] = Semaphore.make(1).map {
    semaphore => new RefMap[K, V](new ConcurrentHashMap[K, Ref[Task, V]](), semaphore)
  }
}
