package polynote.kernel.util

import cats.effect.IO
import cats.syntax.functor._
import fs2.Pipe
import fs2.concurrent.Enqueue
import polynote.kernel.{Result, ResultValue}

import scala.collection.mutable.ListBuffer

class ResultValueCollector extends Enqueue[IO, ResultValue] {
  private val buffer = new ListBuffer[ResultValue]

  def toList: List[ResultValue] = buffer.toList

  def enqueue1(a: ResultValue): IO[Unit] = IO {
    synchronized {
      buffer += a
    }
  }

  def offer1(a: ResultValue): IO[Boolean] = enqueue1(a).as(true)

  def tap: Pipe[IO, ResultValue, ResultValue] = _.evalTap(enqueue1)
  def tapResults: Pipe[IO, Result, Result] = _.evalTap {
    case rv: ResultValue => enqueue1(rv)
    case _ => IO.unit
  }

  def reset(): IO[Unit] = IO(synchronized(buffer.clear()))
}
