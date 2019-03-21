package polynote.kernel.util

import fs2.Pipe
import fs2.concurrent.{Enqueue, Topic}

/**
  * Captures only the ability to Publish, as in [[Topic]], but without the ability to subscribe. This means it can
  * be contravariant.
  */
trait Publish[F[_], -T] {

  def publish1(t: T): F[Unit]

  def publish: Pipe[F, T, Unit]

  def contramap[U](fn: U => T): Publish[F, U] = new Publish[F, U] {
    override def publish1(t: U): F[Unit] = Publish.this.publish1(fn(t))
    override def publish: Pipe[F, U, Unit] = {
      stream => Publish.this.publish(stream.map(fn))
    }
  }

}

object Publish {

  // Allow a Topic to be treated as a Publish
  final case class PublishTopic[F[_], -T, T1 >: T](topic: Topic[F, T1]) extends Publish[F, T] {
    override def publish1(t: T): F[Unit] = topic.publish1(t)
    override def publish: Pipe[F, T, Unit] = topic.publish
  }

  implicit def topicToPublish[F[_], T](topic: Topic[F, T]): Publish[F, T] = PublishTopic(topic)

  final case class PublishEnqueue[F[_], -T, T1 >: T](queue: Enqueue[F, T1]) extends Publish[F, T] {
    override def publish1(t: T): F[Unit] = queue.enqueue1(t)
    override def publish: Pipe[F, T, Unit] = queue.enqueue
  }

  implicit def enqueueToPublish[F[_], T, T1 <: T](enqueue: Enqueue[F, T]): Publish[F, T1] = PublishEnqueue(enqueue)

}
