package polynote.kernel

import polynote.kernel.util.Publish
import zio.{Has, RIO, Task, ZIO}

package object remote {

  type PublishRemoteResponse = Has[Publish[Task, RemoteResponse]]

  object PublishRemoteResponse {
    def apply(response: RemoteResponse): RIO[PublishRemoteResponse, Unit] =
      ZIO.access[PublishRemoteResponse](_.get).flatMap(_.publish1(response))
  }

  type ClientEnv = GlobalEnv with CellEnv with PublishRemoteResponse
    with Has[StartupRequest]
    with Has[TransportClient]
}
