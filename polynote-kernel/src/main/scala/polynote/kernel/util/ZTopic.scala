package polynote.kernel.util

import java.util.concurrent.atomic.AtomicLong

import zio.stream.{Take, ZStream}
import zio.{Cause, Queue, Ref, UIO, UManaged, ZIO, ZQueue}

sealed trait ZTopic[-RA, +EA, -RB, +EB, -A, +B] {
  /**
    * Publish a value to all the subscribers of this topic. The returned effect will complete
    * once all subscribers have received the value.
    */
  def publish(value: A): ZIO[RA, EA, Unit]

  /**
    * Subscribe to this topic. When the managed value is reserved, the contained queue will begin
    * receiving values published thereafter, until the managed value is released. When released,
    * the queue will be shut down, and the subscriber will removed on the next publish.
    *
    * The returned queue can only be read from, not written to.
    */
  def subscribe: UManaged[ZTopic.Subscriber[RB, EB, B]]

  /**
    * Return the current number of subscribers to this topic.
    */
  def subscriberCount: UIO[Int]

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


  trait Subscriber[-R, +E, +A] {
    def take(): ZIO[R, E, Take[Nothing, A]]
    def stream: ZStream[R, E, A]
    def map[B](fn: A => B): Subscriber[R, E, B]
    def mapM[R1 <: R, E1 >: E, B](fn: A => ZIO[R1, E1, B]): Subscriber[R1, E1, B]
  }

  private trait SubscriberWrite[-R, +E, -A] {
    def offer(value: Take[Nothing, A]): UIO[Unit]
    def shutdown(): UIO[Unit]
  }

  def unbounded[A]: UIO[ZTopic[Any, Nothing, Any, Nothing, A, A]] =
    Ref.make(Map.empty[Long, Queue[A]])
      .map(new Queues[A](_, Queue.unbounded[A]))

  def dropping[A](capacity: Int): UIO[ZTopic[Any, Nothing, Any, Nothing, A, A]] =
    Ref.make(Map.empty[Long, Queue[A]])
      .map(new Queues[A](_, Queue.dropping[A](capacity)))

  def bounded[A](capacity: Int): UIO[ZTopic[Any, Nothing, Any, Nothing, A, A]] =
    Ref.make(Map.empty[Long, Queue[A]])
      .map(new Queues[A](_, Queue.bounded(capacity)))

  /**
    * Implementation that allocates a queue for each subscriber
    */
  private class Queues[A](
    subscriberSet: Ref[Map[Long, SubscriberWrite[Any, Nothing, A]]],
    mkQueue: UIO[Queue[Take[Nothing, A]]]
  ) extends ZTopic[Any, Nothing, Any, Nothing, A, A] {

    private val nextSubscriberId = new AtomicLong(0L)
    private val mkSubscriberId = ZIO.effectTotal(nextSubscriberId.getAndIncrement())

    class Subscriber[-R, +E, A1](
      id: Long,
      queue: ZQueue[Any, Nothing, R, E, Take[Nothing, A1], Take[Nothing, A1]]
    ) extends ZTopic.Subscriber[R, E, A1] with ZTopic.SubscriberWrite[R, E, A1] {
      override def offer(value: Take[Nothing, A1]): UIO[Unit] = {
        val send = queue.offer(value).doUntil(identity).unit
        value match {
          case Take.End => removeSubscriber(id) *> send
          case _ => send
        }
      }

      override def take(): ZIO[R, E, Take[Nothing, A1]] = queue.take
      override def stream: ZStream[R, E, A1] = ZStream.fromQueue(queue).unTake
      override def map[B](fn: A1 => B): ZTopic.Subscriber[R, E, B] = new Subscriber(id, queue.map(_.map(fn)))
      override def mapM[R1 <: R, E1 >: E, B](fn: A1 => ZIO[R1, E1, B]): ZTopic.Subscriber[R1, E1, B] = new Subscriber(
        id,
        queue.mapM {
          case Take.Value(a) => fn(a).map(Take.Value(_))
          case Take.End => ZIO.succeed(Take.End)
          case Take.Fail(err) => ZIO.halt(err)
        }
      )

      override def shutdown(): UIO[Unit] = queue.shutdown

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

    def publish(value: A): UIO[Unit] = subscriberSet.get.flatMap {
      subscribers =>
        ZIO.foreachPar_(subscribers) {
          case (_, subscriber) => subscriber.offer(Take.Value(value))
        }
    }

    private def mkSubscriber: UIO[(Long, Queue[Take[Nothing, A]])] = for {
      queue <- mkQueue
      id    <- mkSubscriberId
      _     <- subscriberSet.update(_ + (id -> queue))
    } yield id -> queue

    private def removeSubscriber(id: Long): UIO[Unit] =
      subscriberSet.getAndUpdate(_ - id).flatMap {
        prev => prev.get(id) match {
          case Some(subscriber) => subscriber.offer()
        }
      }


    override def subscribe: UManaged[Subscriber[Any, Nothing, A]] =
      mkSubscriber.toManaged {
        case (id, queue) => subscriberSet.update(_ - id) &> queue.offer(Take.End) *>
      }.map(_._2)

    override def subscriberCount: UIO[Int] = subscriberSet.get.map(_.size)

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

    override def subscribe: UManaged[ZQueue[Any, Nothing, RB, EB, Nothing, B1]] =
      underlying.subscribe.map(_.mapM(from))

    override def subscriberCount: UIO[Int] =
      underlying.subscriberCount
  }

}
