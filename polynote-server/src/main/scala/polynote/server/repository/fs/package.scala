package polynote.server.repository

import java.nio.file.{FileSystemNotFoundException, Path}

import polynote.kernel.{BaseEnv, GlobalEnv}
import zio.{Has, RIO, ULayer, URIO, ZIO, ZLayer}

package object fs {

  type FileSystems = Has[FileSystems.Service]

  object FileSystems {

    trait Service {
      def defaultFilesystem: NotebookFilesystem
      def forScheme(scheme: String): RIO[BaseEnv with GlobalEnv, NotebookFilesystem]
    }

    private final class LocalOnly(val defaultFilesystem: NotebookFilesystem) extends Service {
      override def forScheme(scheme: String): RIO[BaseEnv with GlobalEnv, NotebookFilesystem] = scheme match {
        case "file" => ZIO.succeed(defaultFilesystem)
        case _ => ZIO.fail(new FileSystemNotFoundException(s"Only local filesystems are supported by the current configuration."))
      }
    }

    def local(fs: NotebookFilesystem): Service = new LocalOnly(fs)

    def access: URIO[FileSystems, Service] = ZIO.access[FileSystems](_.get)
    def defaultFilesystem: URIO[FileSystems, NotebookFilesystem] = access.map(_.defaultFilesystem)
    def forScheme(scheme: String): RIO[BaseEnv with GlobalEnv with FileSystems, NotebookFilesystem] = access.flatMap(_.forScheme(scheme))

    def readPathAsString(path: Path): RIO[BaseEnv with FileSystems, String] = access.flatMap(_.defaultFilesystem.readPathAsString(path))
    def writeStringToPath(path: Path, content: String): RIO[BaseEnv with FileSystems, Unit] = access.flatMap(_.defaultFilesystem.writeStringToPath(path, content))

    val live: ULayer[FileSystems] = ZLayer.succeed(new LocalOnly(new LocalFilesystem()))
  }

}
