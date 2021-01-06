package polynote.server.repository

import java.nio.file.{FileSystemNotFoundException, Path}

import polynote.kernel.{BaseEnv, GlobalEnv}
import zio.{Has, RIO, ULayer, URIO, ZIO, ZLayer}

package object fs {

  type FileSystems = Has[FileSystems.Service]

  object FileSystems {

    trait Service {
      def defaultFilesystem: NotebookFilesystem

      def forScheme(scheme: String, props: Map[String, String]): RIO[BaseEnv with GlobalEnv, NotebookFilesystem]
    }

    private final class LocalOnly(val defaultFilesystem: NotebookFilesystem) extends Service {
      override def forScheme(scheme: String, props: Map[String, String]): RIO[BaseEnv with GlobalEnv, NotebookFilesystem] = scheme match {
        case "file" => ZIO.succeed(defaultFilesystem)
        case _ => ZIO.fail(new FileSystemNotFoundException(s"Only local filesystems are supported by the current configuration."))
      }
    }

    private final class LoadingServices(val defaultFilesystem: NotebookFilesystem,
                                        val filesystemList: List[NotebookFilesystemFactory]) extends Service {
      override def forScheme(scheme: String, props: Map[String, String]): RIO[BaseEnv with GlobalEnv, NotebookFilesystem] =
        filesystemList.find(_.scheme == scheme) match {
          case None =>
            RIO.fail(new IllegalArgumentException(s"Cannot find filesystem for scheme: $scheme"))
          case Some(factory) =>
            factory.create(props)
        }
    }

    def local(fs: NotebookFilesystem): Service = new LocalOnly(fs)

    def access: URIO[FileSystems, Service] = ZIO.access[FileSystems](_.get)

    def defaultFilesystem: URIO[FileSystems, NotebookFilesystem] = access.map(_.defaultFilesystem)

    def forScheme(scheme: String, props: Map[String, String]): RIO[BaseEnv with GlobalEnv with FileSystems, NotebookFilesystem] = access.flatMap(_.forScheme(scheme, props))

    def readPathAsString(path: Path): RIO[BaseEnv with FileSystems, String] = access.flatMap(_.defaultFilesystem.readPathAsString(path))

    def writeStringToPath(path: Path, content: String): RIO[BaseEnv with FileSystems, Unit] = access.flatMap(_.defaultFilesystem.writeStringToPath(path, content))

    val live: ULayer[FileSystems] = {
      import scala.jdk.CollectionConverters._
      import java.util.ServiceLoader
      val fs =
        for {
          list <- ZIO(ServiceLoader.load(classOf[NotebookFilesystemFactory]).asScala.toList)
        } yield new LoadingServices(new LocalFilesystem(), list)
      ZLayer.fromEffect(fs.orDie)
    }

    val local: ULayer[FileSystems] = ZLayer.succeed(new LocalOnly(new LocalFilesystem()))
  }

}
