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

  def protocols: Seq[String]

  object Supported {
    def unapply(arg: URI): Option[URI] = {
      Option(arg.getScheme).flatMap(scheme => protocols.find(_ == scheme)).map(_ => arg)
    }
  }
}

object DownloadableFileProvider {
  val providers: List[DownloadableFileProvider] = ServiceLoader.load(classOf[DownloadableFileProvider]).iterator.asScala.toList

  def isSupported(uri: URI): Boolean = Option(uri.getScheme).exists(providers.flatMap(_.protocols).contains)

  def getFile(uri: URI): Option[DownloadableFile] =
    for {
      scheme <- Option(uri.getScheme)
      provider <- providers.find(_.protocols.contains(scheme))
      file <- provider.getFile(uri)
    } yield file
}

class HttpFileProvider extends DownloadableFileProvider {
  override def protocols: Seq[String] = Seq("http", "https")

  override def provide: PartialFunction[URI, DownloadableFile] = {
    case Supported(uri) => HTTPFile(uri)
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
  override def protocols: Seq[String] = Seq("file")

  override def provide: PartialFunction[URI, DownloadableFile] = {
    case Supported(uri) => LocalFile(uri)
  }
}

case class LocalFile(uri: URI) extends DownloadableFile {
  lazy val file = new File(uri)
  override def openStream: IO[InputStream] = IO(new FileInputStream(file))

  override def size: IO[Long] = IO.pure(file.length())
}
