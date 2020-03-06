package polynote.kernel

import java.io.PrintStream

import polynote.env.ops.Location
import zio.{Has, UIO, ZIO, ZLayer}
import zio.blocking.Blocking

import scala.collection.immutable.StringOps

package object logging {
  type Logging = Has[Logging.Service]

  object Logging {

    def live: ZLayer[Blocking, Nothing, Logging] = ZLayer.fromService {
      blocking: Blocking.Service => new Logging.Service.Default(System.err, blocking)
    }

    trait Service {
      def error(msg: String)(implicit location: Location): UIO[Unit]
      def error(msg: String, err: Throwable)(implicit location: Location): UIO[Unit]
      def error(msg: String, err: zio.Cause[Throwable])(implicit location: Location): UIO[Unit]
      def warn(msg: String)(implicit location: Location): UIO[Unit]
      def info(msg: String)(implicit location: Location): UIO[Unit]
      def remote(msg: String): UIO[Unit]
    }

    object Service {

      class Default(out: PrintStream, blocking: Blocking.Service) extends Service {
        private val Red = "\u001b[31m"
        private val Reset = "\u001b[0m"
        private val remotePrefix = "[REMOTE] "
        private val errorPrefix = "[ERROR]".padTo(remotePrefix.length, ' ')
        private val infoPrefix = "[INFO]".padTo(remotePrefix.length, ' ')
        private val warnPrefix = "[WARN]".padTo(remotePrefix.length, ' ')
        private val indent = "".padTo(remotePrefix.length, ' ')
        private val colonIndent = " :".padTo(remotePrefix.length, ' ').reverse

        override def error(msg: String)(implicit location: Location): UIO[Unit] = blocking.effectBlocking {
          out.synchronized {
            val lines = new StringOps(msg).lines
            out.print(Red)
            out.print(errorPrefix)
            if (location.file != "") {
              out.println(s"(Logged from ${location.file}:${location.line})")
              out.print(errorPrefix)
            }
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
            out.print(msg)
            if (location.file != "")
              out.println(s" (Logged from ${location.file}:${location.line})")
            else
              out.println("")
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
            out.print(msg)
            if (location.file != "")
              out.println(s" (Logged from ${location.file}:${location.line})")
            else
              out.println("")
            out.print(colonIndent)
            val squashed = err.squash
            out.println(squashed)
            squashed.getStackTrace.foreach {
              el =>
                out.print(indent)
                out.println(el)
            }
            new StringOps(err.prettyPrint).linesWithSeparators.foreach {
              line =>
                out.print(indent)
                out.print(line)
            }
            out.print(Reset)
          }
        }.ignore

        private def printWithPrefix(prefix: String, msg: String)(implicit location: Location): UIO[Unit] =
          blocking.effectBlocking {
            out.synchronized {
              val lines = new StringOps(msg).lines
              val firstLine = lines.next()
              out.print(prefix)
              out.println(firstLine)
              lines.foreach {
                l =>
                  out.print(indent)
                  out.println(l)
              }
            }
          }.ignore

        override def warn(msg: String)(implicit location: Location): UIO[Unit] = printWithPrefix(warnPrefix, msg)
        override def info(msg: String)(implicit location: Location): UIO[Unit] = printWithPrefix(infoPrefix, msg)
        override def remote(msg: String): UIO[Unit] = printWithPrefix(remotePrefix, msg)(Location.Empty)
      }
    }


    def error(msg: String)(implicit location: Location): ZIO[Logging, Nothing, Unit] = access.flatMap(_.error(msg))
    def error(msg: String, err: Throwable)(implicit location: Location): ZIO[Logging, Nothing, Unit] = access.flatMap(_.error(msg, err))
    def error(msg: String, cause: zio.Cause[Throwable])(implicit location: Location): ZIO[Logging, Nothing, Unit] = access.flatMap(_.error(msg, cause))
    def warn(msg: String)(implicit location: Location): ZIO[Logging, Nothing, Unit] = access.flatMap(_.warn(msg))
    def info(msg: String)(implicit location: Location): ZIO[Logging, Nothing, Unit] = access.flatMap(_.info(msg))
    def remote(msg: String): ZIO[Logging, Nothing, Unit] = access.flatMap(_.remote(msg))
    def access: ZIO[Logging, Nothing, Service] = ZIO.access[Logging](_.get)

  }
}
