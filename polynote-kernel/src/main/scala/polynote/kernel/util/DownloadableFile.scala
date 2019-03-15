package polynote.kernel.util

import java.io.{File, FileInputStream, InputStream}
import java.net.{HttpURLConnection, URI}
import java.util.ServiceLoader

import scala.collection.JavaConverters._
import cats.effect.IO

trait DownloadableFile {
  def openStream: IO[InputStream]
  def size: IO[Long]
}

trait DownloadableFileProvider {
  def getFile(uri: URI): Option[DownloadableFile] = provide.lift(uri)

  def provide: PartialFunction[URI, DownloadableFile]

  object Protocol {
    def unapply(arg: URI): Option[String] = {
      Option(arg.getScheme)
    }
  }
}

object DownloadableFileProvider {
  val providers: List[DownloadableFileProvider] = ServiceLoader.load(classOf[DownloadableFileProvider]).iterator.asScala.toList

  def getFile(uri: URI): Option[DownloadableFile] = providers.flatMap(_.getFile(uri)).headOption
}

class HttpFileProvider extends DownloadableFileProvider {
  override def provide: PartialFunction[URI, DownloadableFile] = {
    case uri @ Protocol("http" | "https") => HTTPFile(uri)
  }
}

case class HTTPFile(uri: URI) extends DownloadableFile {
  override def openStream: IO[InputStream] = IO(uri.toURL.openStream())

  override def size: IO[Long] = IO(uri.toURL.openConnection().asInstanceOf[HttpURLConnection]).bracket { conn =>
    IO {
      conn.setRequestMethod("HEAD")
      conn.getContentLengthLong
    }
  } { conn => IO(conn.disconnect())}
}

class LocalFileProvider extends DownloadableFileProvider {
  override def provide: PartialFunction[URI, DownloadableFile] = {
    case uri @ Protocol("file") => LocalFile(uri)
  }
}

case class LocalFile(uri: URI) extends DownloadableFile {
  lazy val file = new File(uri)
  override def openStream: IO[InputStream] = IO(new FileInputStream(file))

  override def size: IO[Long] = IO.pure(file.length())
}
