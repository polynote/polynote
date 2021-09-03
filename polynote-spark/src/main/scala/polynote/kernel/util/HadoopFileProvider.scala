package polynote.kernel.util

import java.io.InputStream
import java.net.URI
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import zio.{RIO, RManaged, ZManaged}
import zio.blocking.{Blocking, effectBlocking}

class HadoopFileProvider extends DownloadableFileProvider {
  override def protocols: Seq[String] = Seq("hdfs", "hftp", "s3", "s3n", "s3a")

  override def provide: PartialFunction[URI, DownloadableFile] = {
    case Supported(uri) => HadoopFile(uri)
  }
}

case class HadoopFile(uri: URI) extends DownloadableFile {
  lazy val filePath = new Path(uri)
  lazy val fs: FileSystem = filePath.getFileSystem(new Configuration())

  override def openStream: RManaged[Blocking, InputStream] = ZManaged.fromAutoCloseable(effectBlocking(fs.open(filePath)))

  override def size: RIO[Blocking, Long] = effectBlocking(fs.getFileStatus(filePath).getLen)

}
