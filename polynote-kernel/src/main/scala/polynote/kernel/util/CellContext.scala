package polynote.kernel.util

import cats.effect.{Concurrent, IO}
import cats.effect.concurrent.{Deferred, TryableDeferred}
import polynote.kernel.ResultValue
import polynote.messages.CellID

import scala.annotation.tailrec


class CellContext(
  val id: CellID,
  val module: TryableDeferred[IO, scala.reflect.api.Symbols#ModuleSymbol],
  @volatile private var previousContext: Option[CellContext]
) {

  val results = new ResultValueCollector
  def resultValues: List[ResultValue] = results.toList

  def collectBack[A](fn: PartialFunction[CellContext, A]): List[A] = {
    @tailrec def impl(prev: Option[CellContext], res: List[A]): List[A] = prev match {
      case Some(prev) if fn.isDefinedAt(prev) => impl(prev.previousContext, fn(prev) :: res)
      case Some(prev) => impl(prev.previousContext, res)
      case _ => res.reverse
    }
    impl(Some(this), Nil)
  }

  def collectBackWhile[A](fn: PartialFunction[CellContext, A]): List[A] = {
    @tailrec def impl(prev: Option[CellContext], res: List[A]): List[A] = prev match {
      case Some(prev) if fn.isDefinedAt(prev) => impl(prev.previousContext, fn(prev) :: res)
      case _ => res.reverse
    }
    impl(Some(this), Nil)
  }

  def foldBack[A](initial: A)(fn: (A, CellContext) => A): A = {
    @tailrec def impl(prev: Option[CellContext], current: A): A = prev match {
      case Some(prev) => impl(prev.previousContext, fn(current, prev))
      case _ => current
    }
    impl(Some(this), initial)
  }


  def visibleValues: List[ResultValue] = foldBack(Map.empty[String, ResultValue]) {
    (accum, next) => next.resultValues.map(rv => rv.name -> rv).toMap ++ accum
  }.values.toList

  def index: Int = previousContext match {
    case None => 0
    case Some(prev) => prev.index + 1
  }

  def setPrev(previous: CellContext): Unit = setPrev(Some(previous))

  def setPrev(previous: Option[CellContext]): Unit = {
    this.previousContext = previous
  }

  def previous: Option[CellContext] = previousContext

}

object CellContext {
  def apply(id: CellID, previous: Option[CellContext])(implicit concurrent: Concurrent[IO]): IO[CellContext] =
    for {
      module    <- Deferred.tryable[IO, scala.reflect.api.Symbols#ModuleSymbol]
    } yield new CellContext(id, module, previous)

  def apply(id: CellID)(implicit concurrent: Concurrent[IO]): IO[CellContext] = apply(id, None)

  def unsafe(id: CellID, previous: Option[CellContext])(implicit concurrent: Concurrent[IO]): CellContext =
    new CellContext(id, Deferred.tryable[IO, scala.reflect.api.Symbols#ModuleSymbol].unsafeRunSync(), previous)

  def unsafe(id: CellID)(implicit concurrent: Concurrent[IO]): CellContext = unsafe(id, None)

  def unsafe(idInt: Int)(implicit concurrent: Concurrent[IO]): CellContext = unsafe(idInt.toShort)
}