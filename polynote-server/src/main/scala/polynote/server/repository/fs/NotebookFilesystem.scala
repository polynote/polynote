package polynote.server
package repository.fs

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.file.Path

import polynote.kernel.{BaseEnv, GlobalEnv}
import polynote.messages.Message
import scodec.bits.BitVector
import zio.blocking.Blocking
import zio.{RIO, RManaged, Task, ZIO}

/**
  * Encapsulates useful Notebook-related FS stuff.
  *
  * All paths must be absolute.
  */
trait NotebookFilesystem {

  def readPathAsString(path: Path): RIO[BaseEnv, String]

  // TODO: this really should be Managed
  def openNotebookFile(path: Path): RIO[BaseEnv with GlobalEnv, NotebookFile]

  def writeStringToPath(path: Path, content: String): RIO[BaseEnv, Unit]

  def createLog(path: Path): RIO[BaseEnv, WAL.WALWriter]

  def list(path: Path): RIO[BaseEnv, List[Path]]

  def validate(path: Path): RIO[BaseEnv, Unit]

  def exists(path: Path): RIO[BaseEnv, Boolean]

  def move(from: Path, to: Path): RIO[BaseEnv, Unit]

  def copy(from: Path, to: Path): RIO[BaseEnv, Unit]

  def delete(path: Path): RIO[BaseEnv, Unit]

  def init(path: Path): RIO[BaseEnv, Unit]
}

trait NotebookFile {
  def overwrite(content: String): RIO[BaseEnv, Unit]
  def readContent(): RIO[BaseEnv, String]
  def close(): RIO[BaseEnv, Unit]
}
