package polynote.kernel.util

import java.io.InputStream
import java.net.URI

import cats.effect.IO
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}

class HadoopFileProvider extends DownloadableFileProvider {
  override def protocols: Seq[String] = Seq("hdfs", "hftp", "s3", "s3n", "s3a")

  override def provide: PartialFunction[URI, DownloadableFile] = {
    case Supported(uri) => HadoopFile(uri)
  }
}

case class HadoopFile(uri: URI) extends DownloadableFile {
  lazy val filePath = new Path(uri)
  lazy val fs: FileSystem = filePath.getFileSystem(new Configuration())

  override def openStream: IO[InputStream] = IO(fs.open(filePath))

  override def size: IO[Long] = IO(fs.getFileStatus(filePath).getLen)

}
