package polynote.kernel.util

import java.util.concurrent.ConcurrentHashMap

import scala.collection.JavaConverters._
import zio.{Cause, Exit, Promise, Reservation, Semaphore, UIO, ZIO, ZManaged}
import ZIO.{effect, effectTotal}

class ManagedMap[K, V] private (
  underlying: ConcurrentHashMap[K, (V, () => ZIO[Any, Nothing, Any])],
  semaphore: Semaphore,
  closed: Promise[Nothing, Unit]
) {

  private def acquire[R, E](res: Reservation[R, E, V]) = closed.isDone.flatMap {
    case false => res.acquire
    case true  => ZIO.halt(Cause.die(new IllegalStateException("Map is already closed; cannot acquire any more values")))
  }

  private def releaseFn[R](key: K, value: V, release: Exit[Any, Any] => ZIO[R, Nothing, Any], env: R): () => ZIO[Any, Nothing, Unit] =
    () => effectTotal(underlying.remove(key, value)) *> release(Exit.succeed(())).unit.provide(env)

  def getOrCreate[R, E](key: K)(create: => ZManaged[R, E, V]): ZIO[R, E, V] = effectTotal {
    if (underlying.containsKey(key)) {
      Option(underlying.get(key)).map(_._1)
    } else None
  }.get.catchAll {
    _ => semaphore.withPermit {
      effectTotal(underlying.containsKey(key)).flatMap {
        case true  => ZIO.succeed(underlying.get(key)._1)
        case false => create.reserve.flatMap {
          res =>
            for {
              r <- ZIO.environment[R]
              v <- acquire(res)
              _ <- effectTotal(underlying.put(key, (v, releaseFn(key, v, res.release, r))))
            } yield v
        }
      }
    }
  }

  def get(key: K): UIO[Option[V]] = effectTotal(Option(underlying.get(key)).map(_._1))

  def put[R, E](key: K, value: ZManaged[R, E, V]): ZIO[R, E, V] = value.reserve.flatMap {
    res =>
      for {
        r    <- ZIO.environment[R]
        v    <- acquire(res)
        prev <- effectTotal(underlying.put(key, (v, releaseFn(key, v, res.release, r))))
        _    <- if (prev != null) prev._2.apply().as(()) else ZIO.unit
      } yield v
  }

  def remove(key: K): ZIO[Any, Nothing, Boolean] = effectTotal(Option(underlying.remove(key))).flatMap {
    case Some((_, release)) => release().as(true)
    case None => ZIO.succeed(false)
  }

  def close(): UIO[Unit] = closed.succeed(()) *> ZIO.foreachPar_(underlying.asScala.toSeq) {
    case (k, null)    => effectTotal(Option(underlying.remove(k))).as(())
    case (k, (_, release)) => effectTotal(Option(underlying.remove(k))) *> release()
  }

}

object ManagedMap {

}