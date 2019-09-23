package polynote.kernel

import cats.data.{IndexedStateT, State}
import fs2.concurrent.SignallingRef
import polynote.messages.Notebook
import zio.Task

class VersionedNotebookRef[Ver](
  parent: SignallingRef[Task, (Ver, Notebook)]
) extends SignallingRef[Task, Notebook] {

  def get: Task[Notebook] = parent.get.map(_._2)
  def set(a: Notebook): Task[Unit] = parent.update(_.copy(_2 = a))

  override def discrete: fs2.Stream[Task, Notebook] = parent.discrete.map(_._2)
  override def continuous: fs2.Stream[Task, Notebook] = parent.continuous.map(_._2)

  def getAndSet(a: Notebook): Task[Notebook] = parent.modify {
    case (ver, old) => ((ver, a), old)
  }

  def access: Task[(Notebook, Notebook => Task[Boolean])] = parent.access.map {
    case ((ver, t), fn) => (t, tt => fn((ver, tt)))
  }

  def tryUpdate(f: Notebook => Notebook): Task[Boolean] = parent.tryUpdate {
    case (ver, old) => (ver, f(old))
  }

  def tryModify[B](f: Notebook => (Notebook, B)): Task[Option[B]] = parent.tryModify {
    case (ver, old) => f(old) match {
      case (a, b) => ((ver, a), b)
    }
  }

  def update(f: Notebook => Notebook): Task[Unit] = parent.update {
    case (ver, old) => (ver, f(old))
  }

  def modify[B](f: Notebook => (Notebook, B)): Task[B] = parent.modify {
    case (ver, old) => f(old) match {
      case (a, b) => ((ver, a), b)
    }
  }

  // I apologize for this.
  private def adaptState[B](state: State[Notebook, B]): State[(Ver, Notebook), B] = IndexedStateT.applyF {
    state.runF.map {
      fn => (vt: (Ver, Notebook)) => {
        fn(vt._2).map {
          case (tt, b) => ((vt._1, tt), b)
        }
      }
    }
  }

  def tryModifyState[B](state: State[Notebook, B]): Task[Option[B]] = parent.tryModifyState(adaptState(state))
  def modifyState[B](state: State[Notebook, B]): Task[B] = parent.modifyState(adaptState(state))
}
