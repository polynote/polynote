package polynote.kernel.util

import zio.{ZHub, ZIO, ZQueue}
import zio.stream.Take

/**
  * Captures only the ability to Publish, but without the ability to subscribe. This means it can
  * be contravariant.
  */
trait Publish[-R, +E, -T] {

  def publish1(t: T): ZIO[R, E, Unit]

  def contramap[U](fn: U => T): Publish[R, E, U] = new Publish[R, E, U] {
    override def publish1(t: U): ZIO[R, E, Unit] = Publish.this.publish1(fn(t))
  }

  def contraFlatMap[R1 <: R, E1 >: E, U](fn: U => ZIO[R1, E1, T]): Publish[R1, E1, U] = new Publish[R1, E1, U] {
    override def publish1(t: U): ZIO[R1, E1, Unit] = fn(t).flatMap(u => Publish.this.publish1(u))
  }

  def tap[R1 <: R, E1 >: E, T1 <: T](into: T1 => ZIO[R1, E1, Unit]): Publish[R1, E1, T1] = new Publish[R1, E1, T1] {
    def publish1(t: T1): ZIO[R1, E1, Unit] = Publish.this.publish1(t) *> into(t)
  }

  def tap[R1 <: R, E1 >: E, T1 <: T](into: Publish[R1, E1, T1]): Publish[R1, E1, T1] = new Publish[R1, E1, T1] {
    def publish1(t: T1): ZIO[R1, E1, Unit] = Publish.this.publish1(t) *> into.publish1(t)
  }

  def catchAll[R1 <: R](fn: E => ZIO[R1, Nothing, Unit]): Publish[R1, Nothing, T] = new Publish[R1, Nothing, T] {
    override def publish1(t: T): ZIO[R1, Nothing, Unit] = Publish.this.publish1(t).catchAll(fn)
  }

  def provide(env: R): Publish[Any, E, T] = new Publish[Any, E, T] {
    override def publish1(t: T): ZIO[Any, E, Unit] = Publish.this.publish1(t).provide(env)
  }

}

object Publish {

  final case class PublishZTopic[-RA, -RB, +EA, +EB, -A, +B](topic: ZTopic[RA, RB, EA, EB, A, B]) extends Publish[RA, EA, A] {
    override def publish1(t: A): ZIO[RA, EA, Unit] = topic.publish(t)
  }

  implicit def zTopicToPublish[RA, RB, EA, EB, A, B](topic: ZTopic[RA, RB, EA, EB, A, B]): Publish[RA, EA, A] =
    PublishZTopic(topic)

  def apply[RA, RB, EA, EB, A, B](topic: ZTopic[RA, RB, EA, EB, A, B]): Publish[RA, EA, A] = zTopicToPublish(topic)

  def fn[R, E, T](fn: T => ZIO[R, E, Unit]): Publish[R, E, T] = new Publish[R, E, T] {
    override def publish1(t: T): ZIO[R, E, Unit] = fn(t)
  }

  final case class PublishZHub[-RA, -RB, +EA, +EB, -A, +B](hub: ZHub[RA, RB, EA, EB, A, B]) extends Publish[RA, EA, A] {
    override def publish1(t: A): ZIO[RA, EA, Unit] = hub.publish(t).flip.retryUntilEquals(true).flip.unit
  }

  implicit def zHubToPublish[RA, RB, EA, EB, A, B](hub: ZHub[RA, RB, EA, EB, A, B]): Publish[RA, EA, A] = PublishZHub(hub)

  def apply[RA, RB, EA, EB, A, B](hub: ZHub[RA, RB, EA, EB, A, B]): Publish[RA, EA, A] = PublishZHub(hub)

  def ignore[T]: Publish[Any, Nothing, T] = fn(_ => ZIO.unit)

  final case class PublishZQueueTake[RA, EA, RB, EB, ET <: EA, A, B](queue: ZQueue[RA, RB, EA, EB, Take[ET, A], B]) extends Publish[RA, EA, A] {
    override def publish1(t: A): ZIO[RA, EA, Unit] = queue.offer(Take.single(t)).repeatUntil(identity).unit
  }

  implicit def zqueueTakeToPublish[RA, RB, EA, EB, ET <: EA, A, B](queue: ZQueue[RA, RB, EA, EB, Take[ET, A], B]): Publish[RA, EA, A] = PublishZQueueTake(queue)

  def apply[E, E1 <: E, A](queue: zio.Queue[Take[E1, A]])(implicit dummyImplicit: DummyImplicit): Publish[Any, E1, A] = PublishZQueueTake(queue)
}
