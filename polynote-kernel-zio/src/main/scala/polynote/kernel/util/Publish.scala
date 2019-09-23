package polynote.kernel.util

import cats.{FlatMap, Monad}
import cats.effect.Concurrent
import cats.syntax.flatMap._
import fs2.Pipe
import fs2.concurrent.{Enqueue, Topic}

/**
  * Captures only the ability to Publish, as in [[Topic]], but without the ability to subscribe. This means it can
  * be contravariant.
  */
trait Publish[F[+_], -T] {

  def publish1(t: T): F[Unit]

  def publish: Pipe[F, T, Unit] = _.evalMap(publish1)

  def contramap[U](fn: U => T): Publish[F, U] = new Publish[F, U] {
    override def publish1(t: U): F[Unit] = Publish.this.publish1(fn(t))
    override def publish: Pipe[F, U, Unit] = {
      stream => Publish.this.publish(stream.map(fn))
    }
  }

  def contraFlatMap[U](fn: U => F[T])(implicit F: Monad[F]): Publish[F, U] = new Publish[F, U] {
    override def publish1(t: U): F[Unit] = fn(t).flatMap(u => Publish.this.publish1(u))
    override def publish: Pipe[F, U, Unit] = stream => stream.evalMap(publish1)
  }

  def some[U](implicit ev: Option[U] <:< T): Publish[F, U] = contramap[U](u => ev(Option(u)))

  def tap[T1 <: T](into: T1 => F[Unit])(implicit F: FlatMap[F]): Publish[F, T1] = new Publish[F, T1] {
    def publish1(t: T1): F[Unit] = Publish.this.publish1(t).flatMap(_ => into(t))
    override def publish: Pipe[F, T1, Unit] = stream => stream.evalTap(into).through(Publish.this.publish)
  }

  def tap[T1 <: T](into: Publish[F, T1])(implicit F: Concurrent[F]): Publish[F, T1] = new Publish[F, T1] {
    def publish1(t: T1): F[Unit] = Publish.this.publish1(t).flatMap(_ => into.publish1(t))
    override def publish: Pipe[F, T1, Unit] = stream => stream.broadcastThrough(Publish.this.publish, into.publish)
  }

}

object Publish {

  // Allow a Topic to be treated as a Publish
  final case class PublishTopic[F[+_], -T, T1 >: T](topic: Topic[F, T1]) extends Publish[F, T] {
    override def publish1(t: T): F[Unit] = topic.publish1(t)
    override def publish: Pipe[F, T, Unit] = topic.publish
  }

  implicit def topicToPublish[F[+_], T](topic: Topic[F, T]): Publish[F, T] = PublishTopic(topic)

  def apply[F[+_], T](topic: Topic[F, T]): Publish[F, T] = topic

  final case class PublishEnqueue[F[+_], -T, T1 >: T](queue: Enqueue[F, T1]) extends Publish[F, T] {
    override def publish1(t: T): F[Unit] = queue.enqueue1(t)
    override def publish: Pipe[F, T, Unit] = queue.enqueue
  }

  implicit def enqueueToPublish[F[+_], T, T1 <: T](enqueue: Enqueue[F, T]): Publish[F, T1] = PublishEnqueue(enqueue)

  def apply[F[+_], T](enqueue: Enqueue[F, T]): Publish[F, T] = enqueue

  def fn[F[+_], T](fn: T => F[Unit]): Publish[F, T] = new Publish[F, T] {
    override def publish1(t: T): F[Unit] = fn(t)
  }
}
