package polynote.kernel

import polynote.kernel.util.Publish
import zio.{Has, Task}

package object remote {

  type PublishRemoteResponse = Has[Publish[Task, RemoteResponse]]

}
