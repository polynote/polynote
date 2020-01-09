package polynote.server.repository.fs

import java.nio.file.Path

import polynote.kernel.BaseEnv
import zio.RIO

/**
  * Encapsulates useful Notebook-related FS stuff.
  *
  * All paths must be absolute.
  */
trait NotebookFilesystem {

  def readPathAsString(path: Path): RIO[BaseEnv, String]

  def writeStringToPath(path: Path, content: String): RIO[BaseEnv, Unit]

  def list(path: Path): RIO[BaseEnv, List[Path]]

  def validate(path: Path): RIO[BaseEnv, Unit]

  def exists(path: Path): RIO[BaseEnv, Boolean]

  def move(from: Path, to: Path): RIO[BaseEnv, Unit]

  def delete(path: Path): RIO[BaseEnv, Unit]

  def init(path: Path): RIO[BaseEnv, Unit]
}
