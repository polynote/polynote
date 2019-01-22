package polynote.server.repository

import fs2.concurrent.SignallingRef
import polynote.messages.{Notebook, NotebookCell}

class NotebookRef[F[_]](
  ref: SignallingRef[F, Notebook]
) {

  def get: F[Notebook] = ref.get

  def insertCell(cell: NotebookCell, after: Option[String]): F[Unit] = ref.update {
    notebook => notebook
  }

}
