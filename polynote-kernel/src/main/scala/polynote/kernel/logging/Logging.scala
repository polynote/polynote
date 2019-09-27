package polynote.kernel.logging

import java.io.PrintStream

import polynote.env.ops.Location
import polynote.kernel.logging
import zio.blocking.Blocking
import zio.{UIO, ZIO}

trait Logging {
  val logging: Logging.Service
}

object Logging {

  trait Service {
    def error(msg: String)(implicit location: Location): UIO[Unit]
    def error(msg: String, err: Throwable)(implicit location: Location): UIO[Unit]
    def error(msg: String, err: zio.Cause[Throwable])(implicit location: Location): UIO[Unit]
    def warn(msg: String)(implicit location: Location): UIO[Unit]
    def info(msg: String)(implicit location: Location): UIO[Unit]
  }

  object Service {

    class Default(out: PrintStream, blocking: Blocking.Service[Any]) extends Service {
      private val Red = "\u001b[31m"
      private val Reset = "\u001b[0m"
      private val errorPrefix = "[ERROR] "
      private val infoPrefix = "[INFO]".padTo(errorPrefix.length, ' ')
      private val warnPrefix = "[WARN]".padTo(errorPrefix.length, ' ')
      private val indent = "".padTo(errorPrefix.length, ' ')
      private val colonIndent = " :".padTo(errorPrefix.length, ' ').reverse

      override def error(msg: String)(implicit location: Location): UIO[Unit] = blocking.effectBlocking {
        out.synchronized {
          val lines = msg.lines
          out.print(Red)
          out.print(errorPrefix)
          out.println(lines.next())
          lines.foreach {
            l =>
              out.print(indent)
              out.println(l)
          }
          out.print(Reset)
        }
      }.ignore


      override def error(msg: String, err: Throwable)(implicit location: Location): UIO[Unit] = blocking.effectBlocking {
        out.synchronized {
          out.print(Red)
          out.print(errorPrefix)
          out.println(msg)
          out.print(colonIndent)
          out.println(err)
          err.getStackTrace.foreach {
            el =>
              out.print(indent)
              out.println(el)
          }
          out.print(Reset)
        }
      }.ignore

      override def error(msg: String, err: zio.Cause[Throwable])(implicit location: Location): UIO[Unit] = blocking.effectBlocking {
        out.synchronized {
          out.print(Red)
          out.print(errorPrefix)
          out.println(msg)
          out.print(colonIndent)
          val squashed = err.squash
          out.println(squashed)
          squashed.getStackTrace.foreach {
            el =>
              out.print(indent)
              out.println(el)
          }
          err.prettyPrint.lines.foreach {
            line =>
              out.print(indent)
              out.print(line)
          }
          out.print(Reset)
        }
      }.ignore

      override def warn(msg: String)(implicit location: Location): UIO[Unit] = blocking.effectBlocking {
        out.synchronized {
          val lines = msg.lines
          val firstLine = lines.next()
          out.print(warnPrefix)
          out.println(firstLine)
          lines.foreach {
            l =>
              out.print(indent)
              out.println(l)
          }
        }
      }.ignore

      override def info(msg: String)(implicit location: Location): UIO[Unit] = blocking.effectBlocking {
        out.synchronized {
          val lines = msg.lines
          val firstLine = lines.next()
          out.print(infoPrefix)
          out.println(firstLine)
          lines.foreach {
            l =>
              out.print(indent)
              out.println(l)
          }
        }
      }.ignore
    }

  }

  trait Live extends Logging {
    override val logging: Service = new Service.Default(System.err, zio.blocking.Blocking.Live.blocking)
  }

  def error(msg: String)(implicit location: Location): ZIO[Logging, Nothing, Unit] = ZIO.accessM[Logging](_.logging.error(msg))
  def error(msg: String, err: Throwable)(implicit location: Location): ZIO[Logging, Nothing, Unit] = ZIO.accessM[Logging](_.logging.error(msg, err))
  def error(msg: String, cause: zio.Cause[Throwable])(implicit location: Location): ZIO[Logging, Nothing, Unit] = ZIO.accessM[Logging](_.logging.error(msg, cause))
  def warn(msg: String)(implicit location: Location): ZIO[Logging, Nothing, Unit] = ZIO.accessM[Logging](_.logging.warn(msg))
  def info(msg: String)(implicit location: Location): ZIO[Logging, Nothing, Unit] = ZIO.accessM[Logging](_.logging.info(msg))
  def access: ZIO[Logging, Nothing, Service] = ZIO.access[Logging](_.logging)

}
