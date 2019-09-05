package polynote.kernel.dependency

import polynote.kernel.TaskManager
import polynote.messages.NotebookConfig
import zio.TaskR

trait Dependencies {

}

object Dependencies {

  trait Service {

    def apply(notebookConfig: NotebookConfig): TaskR[TaskManager, Nothing]

  }

}
