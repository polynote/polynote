package polynote.kernel.util

import java.util.concurrent.atomic.AtomicLong

import zio.stream.{Take, ZStream}
import zio.{Cause, IO, Managed, Promise, Queue, Ref, Semaphore, UIO, UManaged, ZIO, ZQueue}

sealed trait ZTopic[-RA, +EA, -RB, +EB, -A, +B] {
  /**
    * Publish a value to all the subscribers of this topic. The returned effect will complete
    * once all subscribers have received the value. If the topic is closed, does nothing (as there are no subscribers)
    */
  def publish(value: A): ZIO[RA, EA, Unit]

  def publishAll[R <: RA, E >: EA](stream: ZStream[R, E, A]): ZIO[R, E, Unit] = stream.foreach(publish)

  /**
    * Subscribe to this topic. When the managed value is reserved, the subscriber will begin
    * receiving values published thereafter, until the managed value is released. When released,
    * the subscriber will be shut down and removed.
    *
    * If this topic has been shut down, fails with [[ZTopic.TopicIsClosed]].
    */
  def subscribeManaged: Managed[ZTopic.TopicIsClosed, ZTopic.Subscriber[RB, EB, B]] = subscribe.toManaged(_.shutdown())

  /**
    * Subscribe to this topic. When the returned effect is evaluated, the subscriber will begin
    * receiving values published thereafter, until the managed value is released. When released,
    * the subscriber will be shut down and removed.
    *
    * If this topic has been shut down, fails with [[ZTopic.TopicIsClosed]].
    */
  def subscribe: IO[ZTopic.TopicIsClosed, ZTopic.Subscriber[RB, EB, B]]

  /**
    * If the [[ZTopic.Subscriber]] handle isn't needed, subscribe directly to a stream of published elements. If the
    * topic has already been closed, this will be an empty stream.
    */
  def subscribeStream: ZStream[RB, EB, B] = ZStream.fromEffect(subscribe).catchAll {
    _ => ZStream.empty
  }.flatMap(_.stream)

  /**
    * Return the current number of subscribers to this topic.
    */
  def subscriberCount: UIO[Int]

  /**
    * Shut down this topic; end-of-stream will be published to all subscribers and this topic's references
    * to them will be removed.
    */
  def close(): UIO[Unit]

  /**
    * Modify the type of published value, by providing a function from
    * the new type to the current type.
    */
  final def contramap[A1](fn: A1 => A): ZTopic[RA, EA, RB, EB, A1, B] =
    bimapM(lift(fn), identityM)

  /**
    * Modify the type of published value, by providing an effectful
    * function from the new type to the current type.
    */
  final def contramapM[RA1 <: RA, EA1 >: EA, A1](fn: A1 => ZIO[RA1, EA1, A]): ZTopic[RA1, EA1, RB, EB, A1, B] =
    bimapM(fn, identityM)

  /**
    * Modify the type of emitted value, by providing a function from
    * the current type to the new type.
    */
  final def map[B1](fn: B => B1): ZTopic[RA, EA, RB, EB, A, B1] =
    bimapM(identityM, lift(fn))

  /**
    * Modify the type of emitted value, by providing an effectful
    * function from the current type to the new type.
    */
  final def mapM[RB1 <: RB, EB1 >: EB, B1](fn: B => ZIO[RB1, EB1, B1]): ZTopic[RA, EA, RB1, EB1, A, B1] =
    bimapM(identityM, fn)

  /**
    * Modify the types of both published and emitted values, by providing
    * a function from the new published type to the current published type,
    * and a function from the current emitted type to the new emitted type.
    */
  final def bimap[A1, B1](to: A1 => A, from: B => B1): ZTopic[RA, EA, RB, EB, A1, B1] =
    bimapM(lift(to), lift(from))

  /**
    * Modify the types of both published and emitted values, by providing
    * an effectful function from the new published type to the current
    * published type, and an effectful function from the current emitted
    * type to the new emitted type.
    */
  final def bimapM[RA1 <: RA, EA1 >: EA, RB1 <: RB, EB1 >: EB, A1, B1](
    to: A1 => ZIO[RA1, EA1, A],
    from: B => ZIO[RB1, EB1, B1]
  ): ZTopic[RA1, EA1, RB1, EB1, A1, B1] = new ZTopic.Bimapped(to, from, this)

  private def lift[T, U](fn: T => U): T => UIO[U] =
    fn andThen (u => ZIO.succeed(u))  // TODO: this could be ZIO.succeedNow inside the zio package

  private def identityM[T]: T => UIO[T] =
    t => ZIO.succeed(t) // TODO: this could be ZIO.succeedNow
}

object ZTopic {

  type Of[A] = ZTopic[Any, Nothing, Any, Nothing, A, A]

  trait Subscriber[-R, +E, +A] {

    /**
      * Take one value from this subscriber. Receiving Take.End indicates that this subscriber won't receive any more
      * values. If a stream from [[stream]] is running, it's undefined whether the stream will also get the taken value.
      */
    def take(): ZIO[R, E, Take[Nothing, A]]

    /**
      * A stream of all values published to this subscriber which haven't already been taken by another stream or [[take]].
      * This stream is not multiplexed, so using it in multiple places will be problematic!
      */
    def stream: ZStream[R, E, A]

    def map[B](fn: A => B): Subscriber[R, E, B]
    def mapM[R1 <: R, E1 >: E, B](fn: A => ZIO[R1, E1, B]): Subscriber[R1, E1, B]

    /**
      * Shut down this subscriber; it won't receive any further values and its stream (if any) will be halted.
      */
    def shutdown(): UIO[Unit]

    def isShutdown: UIO[Boolean]
  }

  private trait SubscriberWrite[-R, +E, -In] {
    def offer(value: Take[Nothing, In]): UIO[Unit]
    def shutdown(): UIO[Unit]
    def isShutdown: UIO[Boolean]
  }

  def unbounded[A]: UIO[ZTopic[Any, Nothing, Any, Nothing, A, A]] =
    make(Queue.unbounded[Take[Nothing, A]])

  def dropping[A](capacity: Int): UIO[ZTopic[Any, Nothing, Any, Nothing, A, A]] =
    make(Queue.dropping[Take[Nothing, A]](capacity))

  def bounded[A](capacity: Int): UIO[ZTopic[Any, Nothing, Any, Nothing, A, A]] =
    make(Queue.bounded[Take[Nothing, A]](capacity))

  private def make[A](mkQueue: UIO[Queue[Take[Nothing, A]]]): UIO[ZTopic[Any, Nothing, Any, Nothing, A, A]] = for {
    subscriberSet <- Ref.make(Map.empty[Long, SubscriberWrite[Any, Nothing, A]])
    closed        <- Promise.make[Nothing, Unit]
    closeLock     <- Semaphore.make(1L)
  } yield new Queues[A](subscriberSet, mkQueue, closed, closeLock)

  /**
    * Implementation that allocates a queue for each subscriber
    */
  private class Queues[A](
    subscriberSet: Ref[Map[Long, SubscriberWrite[Any, Nothing, A]]],
    mkQueue: UIO[Queue[Take[Nothing, A]]],
    closed: Promise[Nothing, Unit],
    closeLock: Semaphore
  ) extends ZTopic[Any, Nothing, Any, Nothing, A, A] {

    private val nextSubscriberId = new AtomicLong(0L)
    private val mkSubscriberId = ZIO.effectTotal(nextSubscriberId.getAndIncrement())

    private def ifNotClosed[R, E](zio: ZIO[R, E, Unit]): ZIO[R, E, Unit] =
      closeLock.withPermit(zio.whenM(closed.isDone.map(!_)))

    private class Subscriber[-R, +E, In, Out](
      val id: Long,
      queue: ZQueue[Any, Nothing, R, E, Take[Nothing, In], Take[Nothing, Out]]
    ) extends ZTopic.Subscriber[R, E, Out] with ZTopic.SubscriberWrite[R, E, In] {
      override def offer(value: Take[Nothing, In]): UIO[Unit] = {
        val send = queue.offer(value).doUntil(identity).unit
        value match {
          case Take.End => removeSubscriber(id) *> send
          case _ => send
        }
      }

      override def take(): ZIO[R, E, Take[Nothing, Out]] = queue.isShutdown.flatMap {
        case false =>
          queue.take.tap {
            case Take.End => shutdown()
            case _        => ZIO.unit
          }
        case true => ZIO.succeed(Take.End)
      }

      override lazy val stream: ZStream[R, E, Out] = ZStream.fromQueueWithShutdown(queue).unTake
      override def map[B](fn: Out => B): ZTopic.Subscriber[R, E, B] = new Subscriber(id, queue.map(_.map(fn)))
      override def mapM[R1 <: R, E1 >: E, B](fn: Out => ZIO[R1, E1, B]): ZTopic.Subscriber[R1, E1, B] = new Subscriber(
        id,
        queue.mapM {
          case Take.Value(a) => fn(a).map(Take.Value(_))
          case Take.End => ZIO.succeed(Take.End)
          case Take.Fail(err) => ZIO.halt(err)
        }
      )

      override def shutdown(): UIO[Unit] = queue.shutdown *> removeSubscriber(id)
      override def isShutdown: UIO[Boolean] = queue.isShutdown

//      override def closeAndDrain(): ZIO[R, E, List[A1]] = for {
//        _         <- queue.offer(Take.End)
//        remaining <- queue.takeAll
//        _         <- queue.shutdown
//      } yield remaining.takeWhile {
//        case Take.Value(_) => true
//        case _ => false
//      }.collect {
//        case Take.Value(a) => a
//      }
    }

    private def publishTake(take: Take[Nothing, A]): UIO[Unit] = subscriberSet.get.flatMap {
      subscribers =>
        ZIO.foreachPar_(subscribers) {
          case (_, subscriber) => subscriber.offer(take)
        }
    }

    def publish(value: A): UIO[Unit] = ifNotClosed(publishTake(Take.Value(value)))

    private def mkSubscriber: UIO[Subscriber[Any, Nothing, A, A]] = for {
      queue <- mkQueue
      id    <- mkSubscriberId
      sub    = new Subscriber(id, queue)
      _     <- subscriberSet.update(_ + (id -> sub))
    } yield sub

    private def removeSubscriber(id: Long): UIO[Unit] =
      subscriberSet.getAndUpdate(_ - id).flatMap {
        prev => prev.get(id) match {
          case Some(subscriber) => subscriber.shutdown().whenM(subscriber.isShutdown.map(!_))
          case None             => ZIO.unit
        }
      }

    override def subscribe: IO[TopicIsClosed, ZTopic.Subscriber[Any, Nothing, A]] = closeLock.withPermit {
      closed.isDone.flatMap {
        case false => mkSubscriber
        case true  => ZIO.fail(TopicIsClosed())
      }
    }

    override def subscriberCount: UIO[Int] = subscriberSet.get.map(_.size)

    override def close(): UIO[Unit] =
      closeLock.withPermit(closed.succeed(())) *>
        publishTake(Take.End) *>
        subscriberSet.setAsync(Map.empty)

  }

  /**
    * Implementation that's [contra]mapped over an underlying topic
    */
  private final class Bimapped[-RA, +EA, -RB, +EB, A1, A, B, B1](
    to: A1 => ZIO[RA, EA, A],
    from: B => ZIO[RB, EB, B1],
    underlying: ZTopic[RA, EA, RB, EB, A, B]
  ) extends ZTopic[RA, EA, RB, EB, A1, B1] {

    override def publish(value: A1): ZIO[RA, EA, Unit] =
      to(value) >>= underlying.publish

    override def subscribe: IO[TopicIsClosed, Subscriber[RB, EB, B1]] =
      underlying.subscribe.map(_.mapM(from))

    override def subscribeManaged: Managed[TopicIsClosed, Subscriber[RB, EB, B1]] =
      underlying.subscribeManaged.map(_.mapM(from))

    override def subscriberCount: UIO[Int] =
      underlying.subscriberCount

    override def close(): UIO[Unit] = underlying.close()
  }

  final case class TopicIsClosed() extends Throwable("Topic is already closed")
}
