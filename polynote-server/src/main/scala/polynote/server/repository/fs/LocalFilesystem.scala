package polynote.server.repository.fs
import java.io.{FileNotFoundException, IOException, InputStream, OutputStream}
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.{AtomicMoveNotSupportedException, FileAlreadyExistsException, FileVisitOption, Files, Path, StandardCopyOption, StandardOpenOption}
import java.util.concurrent.atomic.AtomicBoolean

import fs2.Chunk
import polynote.kernel.BaseEnv
import zio.blocking.{Blocking, effectBlocking}
import zio.interop.catz._
import zio.{RIO, Semaphore, Task, ZIO}
import zio.ZIO.effect

import scala.collection.JavaConverters._
import LocalFilesystem.FileChannelWALWriter

class LocalFilesystem(maxDepth: Int = 4) extends NotebookFilesystem {

  override def readPathAsString(path: Path): RIO[BaseEnv, String] = for {
    is      <- effectBlocking(Files.newInputStream(path))
    content <- readBytes(is).ensuring(ZIO.effectTotal(is.close()))
  } yield new String(content.toArray, StandardCharsets.UTF_8)

  /**
    * Writes the content to the given path "atomically" – at least, in a way where the existing file at that path (if
    * present) won't be unrecoverable if writing fails for some reason.
    */
  private def writeAndSwap(path: Path, content: Array[Byte]) =
    try {
      // first, try creating the file in case it doesn't exist. If it doesn't, go ahead and write directly to it.
      // it's not great to use an exception for normal flow control, but I don't think there's another way to avoid a
      // race condition here (e.g. if we checked that the file doesn't exist, but then it does by the time we go to
      // write to it)
      Files.write(path, content, StandardOpenOption.CREATE_NEW)
    } catch {
      case _: FileAlreadyExistsException =>
        // the file already exists, so we're going to write to a temporary file next to it and then swap it.
        // this is to prevent unrecoverable incomplete writes if the application dies during the write.
        val filename = path.getFileName.toString.stripPrefix(".")
        val tmpPath = path.resolveSibling(s".$filename.swp")
        Files.write(tmpPath, content)

        // try an atomic move first to replace the old file with the temp file
        try {
          Files.move(tmpPath, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch {
          case err: AtomicMoveNotSupportedException =>
            // couldn't move atomically; instead copy the original file to an "old" path, move the new file to the path,
            // then delete the old. Then, if the new file doesn't write successfully, then the original is there at the
            // "old" path. AFAIK this code path shouldn't ever happen on a POSIX-compliant system.
            val oldPath = path.resolveSibling(s".$filename.old")
            Files.copy(path, oldPath, StandardCopyOption.REPLACE_EXISTING)

            try {
              Files.move(tmpPath, path)
            } catch {
              case err: Throwable =>
                // failed to move the new file to the path; try to copy the old file back
                Files.copy(oldPath, path)

                // rethrow; don't try to delete the old file in case something is weird.
                throw new IOException(s"Failed to move new file over $path; original file is copied at $oldPath in case of an issue.", err)
            }
            Files.delete(oldPath)
        }
    }

  override def writeStringToPath(path: Path, content: String): RIO[BaseEnv, Unit] = for {
    _      <- createDirs(path)
    bytes  <- effect(content.getBytes(StandardCharsets.UTF_8))
    _      <- effectBlocking(writeAndSwap(path, bytes)).uninterruptible
  } yield ()

  override def createLog(path: Path): RIO[BaseEnv, WAL.WALWriter] =
    effectBlocking(path.getParent.toFile.mkdirs()) *> FileChannelWALWriter(path)

  private def readBytes(is: => InputStream): RIO[BaseEnv, Chunk.Bytes] = {
    for {
      env    <- ZIO.environment[BaseEnv]
      ec      = env.get[Blocking.Service].blockingExecutor.asEC
      chunks <- fs2.io.readInputStream[Task](effectBlocking(is).provide(env), 8192, ec, closeAfterUse = true).compile.toChunk.map(_.toBytes)
    } yield chunks
  }

  override def list(path: Path): RIO[BaseEnv, List[Path]] =
    effectBlocking(Files.walk(path, maxDepth, FileVisitOption.FOLLOW_LINKS).iterator().asScala.drop(1).toList)

  override def validate(path: Path): RIO[BaseEnv, Unit] = {
    if (path.iterator().asScala.length > maxDepth) {
      ZIO.fail(new IllegalArgumentException(s"Input path ($path) too deep, maxDepth is $maxDepth"))
    } else ZIO.unit
  }

  override def exists(path: Path): RIO[BaseEnv, Boolean] = effectBlocking(path.toFile.exists())

  private def createDirs(path: Path): RIO[BaseEnv, Unit] = effectBlocking(Files.createDirectories(path.getParent))

  override def move(from: Path, to: Path): RIO[BaseEnv, Unit] = createDirs(to).map(_ => Files.move(from, to))

  override def copy(from: Path, to: Path): RIO[BaseEnv, Unit] = createDirs(to).map(_ => Files.copy(from, to))

  override def delete(path: Path): RIO[BaseEnv, Unit] =
    exists(path).flatMap {
      case false => ZIO.fail(new FileNotFoundException(s"File $path does't exist"))
      case true  => effectBlocking(Files.delete(path))
    }

  override def init(path: Path): RIO[BaseEnv, Unit] = createDirs(path)
}

object LocalFilesystem {

  final class FileChannelWALWriter private (mkChannel: => FileChannel, lock: Semaphore) extends WAL.WALWriter {

    // lazily open the channel, to avoid creating a WAL just from reading a notebook
    private val channelOpened = new AtomicBoolean(false)
    private lazy val channel: FileChannel = {
      channelOpened.set(true)
      mkChannel
    }

    override protected def append(bytes: ByteBuffer): RIO[Blocking, Unit] = lock.withPermit {
      val buf = bytes.duplicate()
      effectBlocking {
        channel.write(buf)
      }.doWhile(_ => buf.hasRemaining).unit
    }

    override def close(): RIO[Blocking, Unit] = lock.withPermit {
      effectBlocking(channel.close())
    }.when(channelOpened.get())

    override def sync(): RIO[Blocking, Unit] = effectBlocking(channel.force(false))
  }

  object FileChannelWALWriter {
    import StandardOpenOption._
    def apply(path: Path): RIO[Blocking, FileChannelWALWriter] = for {
      lock    <- Semaphore.make(1L)
      channel <- effectBlocking(FileChannel.open(path, WRITE, CREATE_NEW))
    } yield new FileChannelWALWriter(channel, lock)
  }

}