package polynote.server.repository.fs

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import fs2.Chunk
import polynote.kernel.BaseEnv
import zio.blocking.effectBlocking
import zio.{RIO, Task, ZIO}

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
