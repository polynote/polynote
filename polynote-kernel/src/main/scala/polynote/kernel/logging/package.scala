package polynote.kernel

import java.io.PrintStream
import java.util.concurrent.atomic.AtomicReference

import polynote.env.ops.Location
import zio.{Has, UIO, URIO, ZIO, ZLayer}
import zio.blocking.Blocking

import scala.collection.immutable.StringOps

package object logging {
  type Logging = Has[Logging.Service]

  object Logging {

    def live: ZLayer[Blocking, Nothing, Logging] = ZLayer.fromService {
      blocking: Blocking.Service => new Logging.Service.Default(System.err, blocking)
    }

    object Verbosity extends Enumeration {
      type Verbosity = Value
      val Info, Warn, Error = Value

      def getVerbosityFromString(verbosity: String): Verbosity = {
        values.find(_.toString.equalsIgnoreCase(verbosity)).getOrElse(Info)
      }
    }
    import Verbosity._

    trait Service {
      def error(msg: String)(implicit location: Location): UIO[Unit]
      def error(msg: Option[String], err: Throwable)(implicit location: Location): UIO[Unit]
      def errorSync(msg: Option[String], err: Throwable)(implicit location: Location): Unit
      def error(msg: Option[String], err: zio.Cause[Throwable])(implicit location: Location): UIO[Unit]
      def warn(msg: String)(implicit location: Location): UIO[Unit]
      def warn(msg: Option[String], err: Throwable)(implicit location: Location): UIO[Unit]
      def warnSync(msg: String)(implicit location: Location): Unit
      def info(msg: String)(implicit location: Location): UIO[Unit]
      def remote(path: String, msg: String): UIO[Unit]
      def setVerbosity(verbosity: Verbosity): UIO[Unit]
    }

    object Service {

      class Default(out: PrintStream, blocking: Blocking.Service) extends Service {
        private val lastRemote = new AtomicReference[String](null)
        private val Red = "\u001b[31m"
        private val Reset = "\u001b[0m"
        private val remoteIndent = "        |   "
        private val errorPrefix =  "[ERROR]  "
        private val errorIndent =  "   |     "
        private val infoPrefix =   "[INFO]   "
        private val infoIndent =   "   |     "
        private val warnPrefix =   "[WARN]   "
        private val warnIndent = infoIndent
        private var verbosity = Info

        override def error(msg: String)(implicit location: Location): UIO[Unit] = blocking.effectBlocking {
          out.synchronized {
            if (Verbosity.Error >= verbosity) {
              lastRemote.lazySet(null)
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
                  out.print(errorIndent)
                  out.println(l)
              }
              out.print(Reset)
            }
          }
        }.ignore

        private def logStackTraceSync(msg: Option[String], err: Throwable, prefix: String, indent: String, color: String = "")(implicit location: Location): Unit = {
          out.print(color)
          out.print(prefix)
          msg.foreach(out.print)
          if (location.file != "")
            out.println(s" (Logged from ${location.file}:${location.line})")
          else
            out.println("")
          out.print(indent)
          out.println(err)
          err.getStackTrace.foreach {
            el =>
              out.print(indent)
              out.println(el)
          }
          out.print(Reset)
        }

        override def errorSync(msg: Option[String], err: Throwable)(implicit location: Location): Unit =
          {
            if (Verbosity.Error >= verbosity) {
              logStackTraceSync(msg, err, errorPrefix, errorIndent, Red)
            }
          }

        override def error(msg: Option[String], err: Throwable)(implicit location: Location): UIO[Unit] = blocking.effectBlocking {
          out.synchronized {
            if (Verbosity.Error >= verbosity) {
              lastRemote.lazySet(null)
              errorSync(msg, err)
            }
          }
        }.ignore

        override def error(msg: Option[String], err: zio.Cause[Throwable])(implicit location: Location): UIO[Unit] = blocking.effectBlocking {
          out.synchronized {
            if (Verbosity.Error >= verbosity) {
              lastRemote.lazySet(null)
              out.print(Red)
              out.print(errorPrefix)
              msg.foreach(out.print)
              if (location.file != "")
                out.println(s" (Logged from ${location.file}:${location.line})")
              else
                out.println("")
              out.print(errorIndent)
              val squashed = err.squash
              out.println(squashed)
              squashed.getStackTrace.foreach {
                el =>
                  out.print(errorIndent)
                  out.println(el)
              }
              new StringOps(err.prettyPrint).linesWithSeparators.foreach {
                line =>
                  out.print(errorIndent)
                  out.print(line)
              }
              out.print(Reset)
            }
          }
        }.ignore

        private def printWithPrefixSync(prefix: String, indent: String, msg: String)(implicit location: Location): Unit = {
          val lines = new StringOps(msg).lines
          if (lines.hasNext) {
            val firstLine = lines.next()
            out.print(prefix)
            out.println(firstLine)
            lines.foreach {
              l =>
                out.print(indent)
                out.println(l)
            }
          }
        }

        private def printWithPrefix(prefix: String, indent: String, msg: String)(implicit location: Location): UIO[Unit] =
          blocking.effectBlocking {
            out.synchronized {
              printWithPrefixSync(prefix, indent, msg)
            }
          }.ignore

        override def warnSync(msg: String)(implicit location: Location): Unit = {
          if (Verbosity.Warn >= verbosity) {
            printWithPrefixSync(warnPrefix, warnIndent, msg)
          }
        }

        override def warn(msg: String)(implicit location: Location): UIO[Unit] = {
          if (Verbosity.Warn >= verbosity) {
            lastRemote.lazySet(null)
            printWithPrefix(warnPrefix, warnIndent, msg)
          } else {
            UIO.unit
          }
        }

        override def warn(msg: Option[String], err: Throwable)(implicit location: Location): UIO[Unit] = blocking.effectBlocking {
          if (Verbosity.Warn >= verbosity) {
            out.synchronized {
              lastRemote.lazySet(null)
              logStackTraceSync(msg, err, warnPrefix, warnIndent)
            }
          } else {
            UIO.unit
          }
        }.ignore

        override def info(msg: String)(implicit location: Location): UIO[Unit] = {
          if (Verbosity.Info >= verbosity) {
            lastRemote.lazySet(null)
            printWithPrefix(infoPrefix, infoIndent, msg)
          } else {
            UIO.unit
          }
        }

        override def remote(path: String, msg: String): UIO[Unit] = {
          if (lastRemote.getAndSet(path) ne path) {
            val remotePrefix = s"[REMOTE | $path]\n$remoteIndent"
            printWithPrefix(remotePrefix, remoteIndent, msg)(Location.Empty)
          } else {
            printWithPrefix(remoteIndent, remoteIndent, msg)(Location.Empty)
          }
        }

        override def setVerbosity(v: Verbosity): UIO[Unit] = {
          verbosity = v
          UIO.unit
        }
      }
    }


    def error(msg: String)(implicit location: Location): URIO[Logging, Unit] = access.flatMap(_.error(msg))
    def error(msg: String, err: Throwable)(implicit location: Location): URIO[Logging, Unit] = access.flatMap(_.error(Some(msg), err))
    def error(err: Throwable)(implicit location: Location): URIO[Logging, Unit] = access.flatMap(_.error(None, err))
    def error(msg: String, cause: zio.Cause[Throwable])(implicit location: Location): URIO[Logging, Unit] = access.flatMap(_.error(Some(msg), cause))
    def error(cause: zio.Cause[Throwable])(implicit location: Location): URIO[Logging, Unit] = access.flatMap(_.error(None, cause))
    def warn(msg: String)(implicit location: Location): URIO[Logging, Unit] = access.flatMap(_.warn(msg))
    def warn(msg: String, err: Throwable)(implicit location: Location): URIO[Logging, Unit] = access.flatMap(_.warn(Some(msg), err))
    def warn(err: Throwable)(implicit location: Location): URIO[Logging, Unit] = access.flatMap(_.warn(None, err))
    def info(msg: String)(implicit location: Location): URIO[Logging, Unit] = access.flatMap(_.info(msg))
    def remote(path: String, msg: String): URIO[Logging, Unit] = access.flatMap(_.remote(path, msg))
    def access: ZIO[Logging, Nothing, Service] = ZIO.access[Logging](_.get)
    def setVerbosity(verbosity: Verbosity): URIO[Logging,Unit] = access.flatMap(_.setVerbosity(verbosity))

  }
}
