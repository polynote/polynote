package polynote.kernel.util

import java.util.concurrent.{Executor, ExecutorService}

import cats.effect.{ContextShift, IO}
import cats.effect.concurrent.Deferred
import cats.effect.internals.IOContextShift

import scala.concurrent.ExecutionContext

final class ReadySignal(implicit contextShift: ContextShift[IO]) {
  private val signal = Deferred.unsafe[IO, Unit]
  def apply(): IO[Either[Throwable, Unit]] = signal.get.attempt
  def complete: IO[Unit] = signal.complete(()).handleErrorWith(_ => IO.unit)
  def completeSync(): Unit = complete.unsafeRunSync()
}

object ReadySignal {
  def apply()(implicit contextShift: ContextShift[IO]): ReadySignal = new ReadySignal
  def apply(executor: Executor): ReadySignal = {
    implicit val contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.fromExecutor(executor))
    new ReadySignal
  }
}
