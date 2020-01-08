package polynote.server

import polynote.messages.{Notebook, NotebookCell, NotebookConfig, ShortList}

package object repository {

  final case class NotebookContent(cells: List[NotebookCell], config: Option[NotebookConfig]) {
    def toNotebook(path: String) = Notebook(path, ShortList(cells), config)
  }
}
