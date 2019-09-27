package polynote.server

import cats.data.{IndexedStateT, State}
import cats.effect.concurrent.Ref
import fs2.concurrent.SignallingRef
import zio.Task

/**
  * A view of a [[Ref]] over a tuple where the left part is ignored by the view. "Unversioned" because any updates through
  * this view will not result in a version increment (i.e. the left part is the version)
  */
class UnversionedRef[Ver, T](parent: SignallingRef[Task, (Ver, T)]) extends SignallingRef[Task, T] {
  def get: Task[T] = parent.get.map(_._2)
  def set(a: T): Task[Unit] = parent.update(_.copy(_2 = a))

  override def discrete: fs2.Stream[Task, T] = parent.discrete.map(_._2)
  override def continuous: fs2.Stream[Task, T] = parent.continuous.map(_._2)

  def getAndSet(a: T): Task[T] = parent.modify {
    case (ver, old) => ((ver, a), old)
  }

  def access: Task[(T, T => Task[Boolean])] = parent.access.map {
    case ((ver, t), fn) => (t, tt => fn((ver, tt)))
  }

  def tryUpdate(f: T => T): Task[Boolean] = parent.tryUpdate {
    case (ver, old) => (ver, f(old))
  }

  def tryModify[B](f: T => (T, B)): Task[Option[B]] = parent.tryModify {
    case (ver, old) => f(old) match {
      case (a, b) => ((ver, a), b)
    }
  }

  def update(f: T => T): Task[Unit] = parent.update {
    case (ver, old) => (ver, f(old))
  }

  def modify[B](f: T => (T, B)): Task[B] = parent.modify {
    case (ver, old) => f(old) match {
      case (a, b) => ((ver, a), b)
    }
  }

  // I apologize for this.
  private def adaptState[B](state: State[T, B]): State[(Ver, T), B] = IndexedStateT.applyF {
    state.runF.map {
      fn => (vt: (Ver, T)) => {
        fn(vt._2).map {
          case (tt, b) => ((vt._1, tt), b)
        }
      }
    }
  }

  def tryModifyState[B](state: State[T, B]): Task[Option[B]] = parent.tryModifyState(adaptState(state))
  def modifyState[B](state: State[T, B]): Task[B] = parent.modifyState(adaptState(state))
}
