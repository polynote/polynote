package polynote.server

import java.io.InputStream

import fs2.Chunk
import polynote.kernel.BaseEnv
import polynote.messages.{Notebook, NotebookCell, NotebookConfig, ShortList}
import zio.{RIO, Task, ZIO}
import zio.blocking.effectBlocking
import zio.interop.catz._

package object repository {

  final case class NotebookContent(cells: List[NotebookCell], config: Option[NotebookConfig]) {
    def toNotebook(path: String) = Notebook(path, ShortList(cells), config)
  }
}
