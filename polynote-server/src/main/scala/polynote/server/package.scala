package polynote

import cats.{Applicative, MonadError}
import cats.effect.Concurrent
import org.http4s.websocket.WebSocketFrame.Binary
import polynote.kernel.environment.PublishMessage
import polynote.kernel.{BaseEnv, GlobalEnv, TaskG}
import polynote.messages.Message
import polynote.server.auth.{IdentityProvider, UserIdentity}
import zio.{RIO, Task, UIO}

package object server {
  type SessionEnv = BaseEnv with GlobalEnv with UserIdentity with IdentityProvider

  // some cached typeclass instances
  implicit val taskMonadError: MonadError[Task, Throwable] = zio.interop.catz.monadErrorInstance[Any, Throwable]
  implicit val taskConcurrent: Concurrent[Task] = zio.interop.catz.taskConcurrentInstance[Any]
  implicit val uioConcurrent: Concurrent[UIO] = zio.interop.catz.taskConcurrentInstance[Any].asInstanceOf[Concurrent[UIO]]
  implicit val rioApplicativeGlobal: Applicative[TaskG] = zio.interop.catz.taskConcurrentInstance[BaseEnv with GlobalEnv]
  implicit val rioApplicativePublishMessage: Applicative[RIO[PublishMessage, ?]] = zio.interop.catz.taskConcurrentInstance[PublishMessage]

  def toFrame(message: Message): Task[Binary] = {
    Message.encode[Task](message).map(bits => Binary(bits.toByteVector))
  }
}
