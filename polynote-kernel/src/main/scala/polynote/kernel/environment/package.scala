package polynote.kernel

import polynote.app.{Args, MainArgs}
import polynote.config.PolynoteConfig
import polynote.kernel.util.{Publish, UPublish}
import polynote.messages.{Message, Notebook, NotebookUpdate}
import polynote.runtime.KernelRuntime
import zio.{Has, RefM, Task, URIO, ZIO, ZLayer, ZRefM}

package object environment {

  trait BroadcastTag
  type BroadcastMessage = UPublish[Message] with BroadcastTag

  type Config = Has[PolynoteConfig]
  type PublishStatus = Has[UPublish[KernelStatusUpdate]]
  type PublishResult = Has[UPublish[Result]]
  type PublishMessage = Has[UPublish[Message]]

  type BroadcastAll = Has[BroadcastMessage]
  object BroadcastAll {
    def apply(message: Message): URIO[BroadcastAll, Unit] = ZIO.environment[BroadcastAll].flatMap(bc => bc.get.publish(message))
  }

  type CurrentRuntime = Has[KernelRuntime]
  type CurrentNotebook = Has[NotebookRef]

  type TaskRef = RefM[TaskInfo] //ZRefM[Any, Any, Unit, Nothing, TaskInfo, TaskInfo]
  type CurrentTask = Has[TaskRef]
}
