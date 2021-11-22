package polynote.kernel

import polynote.app.{Args, MainArgs}
import polynote.config.PolynoteConfig
import polynote.kernel.util.{Publish, UPublish}
import polynote.messages.{Message, Notebook, NotebookUpdate}
import polynote.runtime.KernelRuntime
import zio.{Has, RefM, Task, ZLayer, ZRefM}

package object environment {

  type Config = Has[PolynoteConfig]
  type PublishStatus = Has[UPublish[KernelStatusUpdate]]
  type PublishResult = Has[UPublish[Result]]
  type PublishMessage = Has[UPublish[Message]]
  type CurrentRuntime = Has[KernelRuntime]
  type CurrentNotebook = Has[NotebookRef]

  type TaskRef = RefM[TaskInfo] //ZRefM[Any, Any, Unit, Nothing, TaskInfo, TaskInfo]
  type CurrentTask = Has[TaskRef]
}
