package polynote.kernel.util

import cats.effect.{Concurrent, IO}
import cats.effect.concurrent.{Deferred, Ref, TryableDeferred}
import polynote.kernel.ResultValue
import polynote.kernel.lang.LanguageInterpreter
import polynote.messages.CellID


case class CellContext(
  id: CellID,
  interpreter: LanguageInterpreter[IO],
  module: TryableDeferred[IO, scala.reflect.api.Symbols#ModuleSymbol],
  previous: Ref[IO, Option[CellContext]], // TODO: maybe just make this @volatile mutable private, with setter/getter – the IO contaminates all the APIs
  index: Int
) {

  val results = new ResultValueCollector
  def resultValues: List[ResultValue] = results.toList

  def collectBack[A](fn: PartialFunction[CellContext, A]): IO[List[A]] = {
    def impl(prev: Ref[IO, Option[CellContext]], res: List[A]): IO[List[A]] = prev.get.flatMap {
      case Some(prev) if fn.isDefinedAt(prev) => impl(prev.previous, fn(prev) :: res)
      case _ => IO.pure(res.reverse)
    }
    impl(previous, Nil)
  }

  def foldBack[A](initial: A)(fn: (A, CellContext) => A): IO[A] = {
    def impl(prev: Ref[IO, Option[CellContext]], current: A): IO[A] = prev.get.flatMap {
      case Some(prev) => impl(prev.previous, fn(current, prev))
      case _ => IO.pure(current)
    }
    impl(previous, initial)
  }

  def visibleValues: IO[List[ResultValue]] = foldBack(Map.empty[String, ResultValue]) {
    (accum, next) => next.resultValues.map(rv => rv.name -> rv).toMap ++ accum
  }.map(_.values.toList)

}

object CellContext {
  def apply(id: CellID, interpreter: LanguageInterpreter[IO], previous: Option[CellContext], index: Int)(implicit concurrent: Concurrent[IO]): IO[CellContext] =
    for {
      module    <- Deferred.tryable[IO, scala.reflect.api.Symbols#ModuleSymbol]
      previous  <- Ref[IO].of(previous)
    } yield CellContext(id, interpreter, module, previous, index)
}