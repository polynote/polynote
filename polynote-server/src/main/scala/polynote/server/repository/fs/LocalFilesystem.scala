package polynote.server.repository.fs
import java.io.{FileNotFoundException, InputStream, OutputStream}
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.{FileVisitOption, Files, Path, StandardOpenOption}
import java.util.concurrent.atomic.AtomicBoolean

import fs2.Chunk
import polynote.kernel.BaseEnv
import zio.blocking.{Blocking, effectBlocking}
import zio.interop.catz._
import zio.{RIO, Semaphore, Task, ZIO}

import scala.collection.JavaConverters._
import LocalFilesystem.FileChannelWALWriter

class LocalFilesystem(maxDepth: Int = 4) extends NotebookFilesystem {

  override def readPathAsString(path: Path): RIO[BaseEnv, String] = for {
    is      <- effectBlocking(Files.newInputStream(path))
    content <- readBytes(is).ensuring(ZIO.effectTotal(is.close()))
  } yield new String(content.toArray, StandardCharsets.UTF_8)

  override def writeStringToPath(path: Path, content: String): RIO[BaseEnv, Unit] = for {
    _ <- createDirs(path)
    _ <- effectBlocking(Files.write(path, content.getBytes(StandardCharsets.UTF_8))).uninterruptible
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