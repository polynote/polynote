package polynote.kernel

import cats.effect.concurrent.Ref
import fs2.Stream
import polynote.app.{Args, MainArgs}
import polynote.config.PolynoteConfig
import polynote.kernel.util.Publish
import polynote.messages.{Message, Notebook, NotebookUpdate}
import polynote.runtime.KernelRuntime
import zio.{Has, Task, ZLayer}

package object environment {

  type Config = Has[PolynoteConfig]
  type PublishStatus = Has[Publish[Task, KernelStatusUpdate]]
  type PublishResult = Has[Publish[Task, Result]]
  type PublishMessage = Has[Publish[Task, Message]]
  type CurrentTask = Has[Ref[Task, TaskInfo]]
  type CurrentRuntime = Has[KernelRuntime]
  type CurrentNotebook = Has[NotebookRef]
  type NotebookUpdates = Has[Stream[Task, NotebookUpdate]]

}
