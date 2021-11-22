package polynote.kernel.util

import java.io.{File, FileInputStream, InputStream}
import java.net.{HttpURLConnection, URI}
import java.util.ServiceLoader
import scala.collection.JavaConverters._
import zio.{RIO, RManaged, ZIO, ZManaged}
import zio.blocking.{Blocking, effectBlocking}

trait DownloadableFile {
  def openStream: RManaged[Blocking, InputStream]
  def size: RIO[Blocking, Long]
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
  private lazy val unsafeLoad = ServiceLoader.load(classOf[DownloadableFileProvider]).iterator.asScala.toList

  def isSupported(uri: URI): RIO[Blocking, Boolean] = effectBlocking(unsafeLoad).map { providers =>
    Option(uri.getScheme).exists(providers.flatMap(_.protocols).contains)
  }

  def getFile(uri: URI): RIO[Blocking, DownloadableFile] = {
    effectBlocking(unsafeLoad).map {
      providers =>
        for {
          scheme <- Option(uri.getScheme)
          provider <- providers.find(_.protocols.contains(scheme))
          file <- provider.getFile(uri)
        } yield file
    }.someOrFail(new Exception(s"Unable to find provider for uri $uri"))
  }
}

class HttpFileProvider extends DownloadableFileProvider {
  override def protocols: Seq[String] = Seq("http", "https")

  override def provide: PartialFunction[URI, DownloadableFile] = {
    case Supported(uri) => HTTPFile(uri)
  }
}

case class HTTPFile(uri: URI) extends DownloadableFile {
  override def openStream: RManaged[Blocking, InputStream] = ZManaged.fromAutoCloseable(effectBlocking(uri.toURL.openStream()))

  override def size: RIO[Blocking, Long] =
    effectBlocking(uri.toURL.openConnection().asInstanceOf[HttpURLConnection])
      .toManaged(conn => ZIO(conn.disconnect()).orDie)
      .use { conn =>
        effectBlocking {
          conn.setRequestMethod("HEAD")
          conn.getContentLengthLong
        }
      }
}

class LocalFileProvider extends DownloadableFileProvider {
  override def protocols: Seq[String] = Seq("file")

  override def provide: PartialFunction[URI, DownloadableFile] = {
    case Supported(uri) => LocalFile(uri)
  }
}

case class LocalFile(uri: URI) extends DownloadableFile {
  lazy val file = new File(uri)
  override def openStream: RManaged[Blocking, InputStream] = ZManaged.fromAutoCloseable(effectBlocking(new FileInputStream(file)))

  override def size: RIO[Blocking, Long] = effectBlocking(file.length())
}
