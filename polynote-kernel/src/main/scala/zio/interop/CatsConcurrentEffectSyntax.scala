package zio.interop

import cats.effect.ConcurrentEffect
import zio.{ IO, RIO, Runtime, Task, ZIO }

import scala.language.implicitConversions

trait CatsConcurrentEffectSyntax {
  implicit final def ZIOConcurrentEffectOps(@deprecated("", "") zio: ZIO.type): CatsConcurrentEffectSyntax.zioOps.type =
    CatsConcurrentEffectSyntax.zioOps
  implicit final def RIOConcurrentEffectOps(@deprecated("", "") rio: RIO.type): CatsConcurrentEffectSyntax.zioOps.type =
    CatsConcurrentEffectSyntax.zioOps

  implicit final def IOConcurrentEffectOps(@deprecated("", "") io: IO.type): CatsConcurrentEffectSyntax.ioOps.type =
    CatsConcurrentEffectSyntax.ioOps
  implicit final def TaskConcurrentEffectOps(@deprecated("", "") io: Task.type): CatsConcurrentEffectSyntax.ioOps.type =
    CatsConcurrentEffectSyntax.ioOps
}

private[interop] object CatsConcurrentEffectSyntax {
  object zioOps {
    final def concurrentEffect[R]: ZIO[R, Nothing, ConcurrentEffect[RIO[R, *]]] =
      ZIO.runtime.map(catz.taskEffectInstance(_: Runtime[R]))
    final def concurrentEffectWith[R, E, A](f: ConcurrentEffect[RIO[R, *]] => ZIO[R, E, A]): ZIO[R, E, A] =
      ZIO.runtime.flatMap(f apply catz.taskEffectInstance(_: Runtime[R]))
  }
  object ioOps {
    final def concurrentEffect: ZIO[Any, Nothing, ConcurrentEffect[RIO[Any, *]]] =
      ZIO.runtime.map(catz.taskEffectInstance(_: Runtime[Any]))
    def concurrentEffectWith[E, A](f: ConcurrentEffect[RIO[Any, *]] => ZIO[Any, E, A]): ZIO[Any, E, A] =
      ZIO.runtime.flatMap(f apply catz.taskEffectInstance(_: Runtime[Any]))
  }
}
