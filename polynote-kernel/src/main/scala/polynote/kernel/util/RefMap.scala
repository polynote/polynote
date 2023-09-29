package polynote.kernel.util

import zio.stm.TReentrantLock
import zio.{Promise, RIO, Ref, UIO, ZIO}

import java.util.concurrent.ConcurrentHashMap
import scala.collection.JavaConverters._


/**
  * A mutable map of concurrent references.
  */
class RefMap[K, V] private (
  underlying: ConcurrentHashMap[K, Either[Promise[Throwable, V], Ref[V]]],
  lock: TReentrantLock
) {

  def getOrCreate[R](key: K)(create: => RIO[R, V]): RIO[R, V] = synchronized {
    if (underlying.containsKey(key)) {
      underlying.get(key) match {
        case Left(promise) => promise.await
        case Right(ref)    => ref.get
      }
    } else {
      lock.writeLock.use { _ =>
        Promise.make[Throwable, V].flatMap {
          promise =>
            underlying.get(key) match {
              case null =>
                for {
                  _     <- ZIO.effectTotal(underlying.put(key, Left(promise))).unit
                  value <- create.tapError(err => ZIO.effectTotal(underlying.remove(key)).unit *> promise.fail(err))
                  ref   <- Ref.make[V](value)
                  _     <- ZIO.effectTotal(underlying.put(key, Right(ref))).unit
                  _     <- promise.succeed(value)
                } yield value
              case Left(promise) => promise.await
              case Right(ref)    => ref.get
            }
        }
      }
    }
  }

  def get(key: K): UIO[Option[V]] = ZIO.effectTotal(Option(underlying.get(key))).flatMap {
    case Some(Left(promise)) => promise.await.option
    case Some(Right(ref)) => ref.get.asSome
    case None => ZIO.none
  }

  def put(key: K, value: V): UIO[Option[V]] = lock.writeLock.use {
    _ =>
      ZIO.effectTotal(Option(underlying.get(key))).flatMap {
        case Some(Right(ref)) => ref.modify(old => old -> value).asSome
        case Some(Left(p))    => p.await.ignore *> put(key, value)
        case None =>
          for {
            ref <- Ref.make[V](value)
            _   <- ZIO.effectTotal(underlying.put(key, Right(ref))).unit
          } yield None
      }
  }

  def remove(key: K): UIO[Option[V]] = lock.writeLock.use {
    _ =>
      ZIO.effectTotal(Option(underlying.remove(key))).flatMap {
        case Some(Right(ref)) => ref.get.asSome
        case Some(Left(p))    => p.await.option
        case None             => ZIO.none
      }
  }

  def keys: UIO[List[K]] = ZIO.effectTotal(underlying.keys().asScala.toList)

  def values: UIO[List[V]] = ZIO.collectAll(underlying.values().asScala.toList.collect {
    case Right(ref) => ref.get
  })

  def entries: UIO[List[(K, V)]] = ZIO.collectAll(underlying.asScala.toList.collect {
    case (key, Right(ref)) => ref.get.map(value => (key, value))
  })

  def isEmpty: UIO[Boolean] = ZIO.effectTotal(underlying.isEmpty)
}

object RefMap {
  def empty[K, V]: UIO[RefMap[K, V]] = TReentrantLock.make.commit.map {
    lock => new RefMap[K, V](new ConcurrentHashMap[K, Either[Promise[Throwable, V], Ref[V]]](), lock)
  }

  def of[K, V](initialValues: (K, V)*): ZIO[Any, Throwable, RefMap[K, V]] = TReentrantLock.make.commit.flatMap {
    lock => ZIO.foreach(initialValues.toList) {
      case (k, v) => Ref.make[V](v).map {
        ref => (k, Right(ref))
      }
    }.map {
      initialRefs => new RefMap[K, V](
        new ConcurrentHashMap[K, Either[Promise[Throwable, V], Ref[V]]](initialRefs.toMap.asJava), lock)
    }
  }
}
