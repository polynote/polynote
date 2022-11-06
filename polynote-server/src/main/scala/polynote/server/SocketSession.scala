package polynote.server

import cats.instances.list._
import cats.syntax.traverse._
import polynote.buildinfo.BuildInfo
import polynote.kernel.util.{Publish, UPublish}
import polynote.kernel.BaseEnv
import polynote.kernel.environment.{BroadcastAll, BroadcastTag, Config, Env, PublishMessage}
import polynote.kernel.interpreter.Interpreter
import polynote.kernel.logging.Logging
import polynote.messages._
import polynote.server.auth.IdentityProvider.checkPermission
import polynote.server.auth.{IdentityProvider, Permission, UserIdentity}
import uzhttp.websocket.Frame
import zio.stream.{Stream, Take, UStream, ZStream}
import zio.{IO, Promise, Queue, RIO, URIO, ZIO}

import java.io.FileNotFoundException
import scala.collection.immutable.SortedMap
import scala.io.Source

object SocketSession {

  def apply(in: Stream[Throwable, Frame], broadcastMessages: UStream[Message]): URIO[SessionEnv with NotebookManager with BroadcastAll, Stream[Throwable, Frame]] =
    for {
      output          <- Queue.unbounded[Take[Nothing, Message]]
      publishMessage  <- Env.add[SessionEnv with NotebookManager with BroadcastAll](Publish(output))
      env             <- ZIO.environment[SessionEnv with NotebookManager with PublishMessage with BroadcastAll]
      closed          <- Promise.make[Throwable, Unit]
      _               <- broadcastMessages.interruptWhen(closed.await.run).foreach(publishMessage.publish).forkDaemon
      close            = closeQueueIf(closed, output)
    } yield parallelStreams(
        toFrames(ZStream.fromEffect(handshake) ++ Stream.fromQueue(output).flattenTake),
        in.handleMessages(close)(handler) ++ closeStream(closed, output),
        keepaliveStream(closed)).provide(env).catchAllCause {
      cause =>
        ZStream.empty
    }

  private val handler: Message => RIO[SessionEnv with BroadcastAll with NotebookManager, Option[Message]] = { message =>
    val result = message match {
      case ListNotebooks(_) =>
        NotebookManager.list().map {
          nbs => Some(ListNotebooks(nbs))
        }

      case CreateNotebook(path, maybeContent, maybeTemplatePath) =>
        NotebookManager.assertValidPath(path) *>
          checkPermission(Permission.CreateNotebook(path)) *> getMaybeContent(maybeContent, maybeTemplatePath).flatMap(content =>
          NotebookManager.create(path, content).as(None))

      case RenameNotebook(path, newPath) =>
        (NotebookManager.assertValidPath(path) &> NotebookManager.assertValidPath(newPath)) *>
          checkPermission(Permission.CreateNotebook(newPath)) *> checkPermission(Permission.DeleteNotebook(path)) *>
          NotebookManager.rename(path, newPath).as(None)

      case CopyNotebook(path, newPath) =>
        (NotebookManager.assertValidPath(path) &> NotebookManager.assertValidPath(newPath)) *>
          checkPermission(Permission.CreateNotebook(newPath)) *>
          NotebookManager.copy(path, newPath).as(None)

      case DeleteNotebook(path) =>
        NotebookManager.assertValidPath(path) *>
          checkPermission(Permission.DeleteNotebook(path)) *> NotebookManager.delete(path).as(None)

      case RunningKernels(_) => getRunningKernels.asSome

      case KeepAlive(payload) =>
        // echo received KeepAlive message back to client.
        ZIO.succeed(Option(KeepAlive(payload)))

      case SearchNotebooks(query, _) => NotebookManager.search(query).map { results => Some(SearchNotebooks(query, results)) }

      case other =>
        ZIO.succeed(None)
    }

    result.catchAll {
      err => Logging.error(err).as(Some(Error(0, err)))
    }
  }

  def handshake: RIO[SessionEnv, ServerHandshake] =
    for {
      factories <- Interpreter.Factories.access
      identity  <- UserIdentity.access
      config    <- Config.access
    } yield ServerHandshake(
      (SortedMap.empty[String, String] ++ factories.mapValues(_.head.languageName)).asInstanceOf[TinyMap[TinyString, TinyString]],
      serverVersion = BuildInfo.version,
      serverCommit = BuildInfo.commit,
      identity = identity.map(i => Identity(i.name, i.avatar.map(ShortString))),
      sparkTemplates = config.spark.flatMap(_.propertySets).getOrElse(Nil),
      notebookTemplates = config.behavior.notebookTemplates,
      notifications = config.notifications == "release_notifications"
    )

  def getRunningKernels: RIO[SessionEnv with NotebookManager, RunningKernels] = for {
    paths          <- NotebookManager.listRunning()
    statuses       <- ZIO.collectAllPar(paths.map(NotebookManager.status))
    kernelStatuses  = paths.zip(statuses).map { case (p, s) => ShortString(p) -> s }
  } yield RunningKernels(kernelStatuses)

  def getMaybeContent(maybeContent: Option[String], maybeTemplatePath: Option[String]): ZIO[Config, Throwable, Option[String]] = {
    maybeContent match {
      case Some(content) => ZIO.succeed(Some(content))
      case None => for {
        config  <- Config.access.map(_.behavior.notebookTemplates)
        content <- maybeTemplatePath match {
          case Some(templatePath) if config.contains(templatePath) => readFromTemplatePath(templatePath)
          case Some(templatePath) => ZIO.fail(new IllegalArgumentException(s"$templatePath is not a configured notebook template"))
          case _ => ZIO.succeed(None)
        }
      } yield content
    }
  }

  def readFromTemplatePath(templatePath: String): IO[FileNotFoundException, Some[String]] = {
    try {
      val content = Source.fromFile(templatePath).mkString
      ZIO.succeed(Some(content))
    } catch {
      case e: FileNotFoundException => ZIO.fail(e)
    }
  }
}
