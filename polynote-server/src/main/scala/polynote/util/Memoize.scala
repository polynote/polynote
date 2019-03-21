package polynote.util

import java.util.concurrent.atomic.AtomicBoolean

import cats.effect.{Concurrent, IO}
import cats.effect.concurrent.{Deferred, Semaphore}
import cats.syntax.apply._
import cats.syntax.functor._

import scala.concurrent.CancellationException

/**
  * An IO that will evaluate lazily once and be memoized for all further access.
  *
  * If the given IO task fails, then the failure will also be memoized, raising an error upon every access.
  */
final class Memoize[A] private (io: IO[A], deferred: Deferred[IO, Either[Throwable, A]], semaphore: Semaphore[IO]) {
  private val completed = new AtomicBoolean(false)

  def get: IO[A] = semaphore.tryAcquire.flatMap {
    case true  => io.guarantee(IO(completed.set(true))).attempt.flatMap {
      result => deferred.complete(result) *> IO.fromEither(result)
    }
    case false => deferred.get.flatMap(IO.fromEither)
  }

  /**
    * Try to get the value, but don't throw an error if it was cancelled
    */
  def tryGet: IO[Option[A]] = get.map(Option.apply).handleErrorWith {
    case err: CancellationException => IO.pure(None)
    case err => IO.raiseError(err)
  }

  /**
    * Query whether the task has started
    */
  def started: IO[Boolean] = semaphore.tryAcquire.flatMap {
    case true  => semaphore.release.as(true)
    case false => IO.pure(false)
  }

  /**
    * If the task has not yet started evaluating, never evaluate it – access to `get` will raise an error. If the task
    * has already started, raise an error.
    */
  def cancel(): IO[Unit] = semaphore.tryAcquire.flatMap {
    case true  => deferred.complete(Left(new CancellationException("Task was cancelled")))
    case false => IO.raiseError(Memoize.AlreadyStarted())
  }

  /**
    * If the task has not yet started evaluating, never evaluate it – access to `get` will raise an error. If the task
    * has already started, return `false` to indicate it could not be cancelled.
    */
  def tryCancel(): IO[Boolean] = cancel().as(true).handleErrorWith {
    case Memoize.AlreadyStarted() => IO.pure(false)
    case err => IO.raiseError(err)
  }

}

object Memoize {
  def apply[A](io: IO[A])(implicit concurrent: Concurrent[IO]): IO[Memoize[A]] = for {
    deferred <- Deferred[IO, Either[Throwable, A]]
    semaphore <- Semaphore[IO](1)
  } yield new Memoize(io, deferred, semaphore)

  def unsafe[A](io: IO[A])(implicit concurrent: Concurrent[IO]): Memoize[A] = {
    val deferred = Deferred.unsafe[IO, Either[Throwable, A]]
    val semaphore = Semaphore[IO](1).unsafeRunSync()
    new Memoize(io, deferred, semaphore)
  }

  def get[A](io: IO[A])(implicit concurrent: Concurrent[IO]): IO[A] = {
    val memoize = unsafe(io)
    memoize.get
  }

  case class AlreadyStarted() extends IllegalStateException("Task has already started")
}
