package polynote.server.auth

import java.util.ServiceLoader

import cats.syntax.either._
import cats.syntax.traverse._
import cats.instances.either._
import cats.instances.option._
import io.circe.{Decoder, Encoder, Json, JsonObject}
import org.http4s.{Request, Response}
import polynote.config.AuthProvider
import polynote.env.ops.Enrich
import polynote.kernel.environment.{Config, CurrentNotebook, Env}
import polynote.kernel.{BaseEnv, TaskB}
import polynote.messages.CellID
import polynote.server.Server.Routes
import zio.{RIO, Task, UIO, URIO, ZIO}
import polynote.server.auth
import zio.interop.catz._
import zio.blocking.effectBlocking
import ZIO.effectTotal

trait Identity {
  def name: String
  def avatar: Option[String] = None
}

class BasicIdentity(val name: String) extends Identity with Serializable
object BasicIdentity {
  def apply(name: String): Identity = new BasicIdentity(name)
}

sealed abstract class PermissionType(val encoded: String)
object PermissionType {
  case object ReadNotebook extends PermissionType("read")
  case object ModifyNotebook extends PermissionType("modify")
  case object ExecuteCell extends PermissionType("execute")
  case object CreateNotebook extends PermissionType("create")
  case object DeleteNotebook extends PermissionType("delete")

  val All: Set[PermissionType] = Set(ReadNotebook, ModifyNotebook, ExecuteCell, CreateNotebook, DeleteNotebook)

  def fromString(string: String): Either[String, PermissionType] = All.find(_.encoded == string).map(Right(_))
    .getOrElse(Left(s"$string is not a valid permission type"))

  implicit val decoder: Decoder[PermissionType] = Decoder.decodeString.emap(fromString)
  implicit val encoder: Encoder[PermissionType] = Encoder.encodeString.contramap(_.encoded)

  private val setStringDecoder: Decoder[Set[PermissionType]] = Decoder.decodeString.flatMap {
    case "all" => Decoder.const(All)
    case str   => fromString(str).fold(Decoder.failedWithMessage, Decoder.const).map(Set(_))
  }

  implicit val setDecoder: Decoder[Set[PermissionType]] = setStringDecoder or Decoder.decodeSet(decoder)
}

sealed abstract class Permission(val permissionType: PermissionType)

object Permission {
  case class ReadNotebook(path: String) extends Permission(PermissionType.ReadNotebook)
  case class ModifyNotebook(path: String) extends Permission(PermissionType.ModifyNotebook)
  case class ExecuteCell(path: String, id: CellID) extends Permission(PermissionType.ExecuteCell)
  case class CreateNotebook(path: String) extends Permission(PermissionType.CreateNotebook)
  case class DeleteNotebook(path: String) extends Permission(PermissionType.DeleteNotebook)

  case class PermissionDenied(permission: Permission, reason: String) extends Throwable(s"Permission denied: $permission ($reason)")
}


/**
  * A service interface for loading identity providers from plug-ins.
  */
trait ProviderLoader {
  def providerKey: String
  def provider(config: JsonObject): RIO[BaseEnv with Config, IdentityProvider.Service]
}

