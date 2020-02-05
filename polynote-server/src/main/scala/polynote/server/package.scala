package polynote

import cats.{Applicative, MonadError}
import cats.effect.{Concurrent, ConcurrentEffect, ContextShift, Timer}
import org.http4s.websocket.WebSocketFrame.Binary
import polynote.kernel.environment.PublishMessage
import polynote.kernel.{BaseEnv, GlobalEnv, TaskG}
import polynote.messages.Message
import polynote.server.auth.{IdentityProvider, UserIdentity}
import zio.{RIO, Task, UIO, ZIO}

package object server {
  type SessionEnv = BaseEnv with GlobalEnv with UserIdentity with IdentityProvider
  
  // some cached typeclass instances
  import zio.interop.{catz => interop}
  implicit val taskTimer: Timer[Task] = interop.implicits.ioTimer[Throwable]
  implicit val taskMonadError: MonadError[Task, Throwable] = interop.monadErrorInstance[Any, Throwable]
  implicit val taskConcurrent: Concurrent[Task] = interop.taskConcurrentInstance[Any]
  implicit val taskGConcurrent: Concurrent[TaskG] = interop.taskConcurrentInstance[BaseEnv with GlobalEnv]
  implicit val sessionConcurrent: Concurrent[RIO[SessionEnv, ?]] = interop.taskConcurrentInstance[SessionEnv]
  implicit val contextShiftTask: ContextShift[Task] = interop.zioContextShift[Any, Throwable]
  implicit val uioConcurrent: Concurrent[UIO] = interop.taskConcurrentInstance[Any].asInstanceOf[Concurrent[UIO]]
  implicit val rioApplicativeGlobal: Applicative[TaskG] = interop.taskConcurrentInstance[BaseEnv with GlobalEnv]
  implicit val rioApplicativePublishMessage: Applicative[RIO[PublishMessage, ?]] = interop.taskConcurrentInstance[PublishMessage]

  def toFrame(message: Message): Task[Binary] = {
    Message.encode[Task](message).map(bits => Binary(bits.toByteVector))
  }

}
