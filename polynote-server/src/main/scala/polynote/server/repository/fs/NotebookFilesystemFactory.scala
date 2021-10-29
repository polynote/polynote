package polynote.server.repository.fs

import polynote.kernel.{BaseEnv, GlobalEnv}
import zio.{RIO, ZIO}

trait NotebookFilesystemFactory {
  def name: String

  def scheme: String

  def create(props: Map[String, String]): RIO[BaseEnv with GlobalEnv, NotebookFilesystem]
}