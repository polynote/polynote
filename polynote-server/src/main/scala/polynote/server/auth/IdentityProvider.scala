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
}

case class BasicIdentity(name: String) extends Identity

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

trait IdentityProvider {
  def identityProvider: IdentityProvider.Service
}

object IdentityProvider {

  trait Service {
    /**
      * Any special endpoints or pages that the provider has to mount, if there are any.
      */
    def authRoutes: Option[Routes]

    /**
      * Check the authorization for a request, failing with a Response (if not authorized) or succeeding with an
      * Identity (if authorized) or None to indicate authorized anonymous access
      */
    def checkAuth(req: Request[Task]): ZIO[BaseEnv with Config, Response[Task], Option[Identity]]

    /**
      * Check whether the given identity (or None for anonymous) has the given permission. If not, the returned task
      * should fail with PermissionDenied.
      */
    def checkPermission(ident: Option[Identity], permission: Permission): ZIO[BaseEnv with Config, Permission.PermissionDenied, Unit]
  }

  val noneService: Service = new Service {
    def authRoutes: Option[Routes] = None
    def checkAuth(req: Request[Task]): ZIO[BaseEnv with Config, Response[Task], Option[Identity]] = ZIO.succeed(None)
    def checkPermission(ident: Option[Identity], permission: Permission): ZIO[BaseEnv with Config, Permission.PermissionDenied, Unit] = ZIO.unit
  }

  val none: IdentityProvider = of(noneService)

  def of(service: Service): IdentityProvider = new IdentityProvider {
    def identityProvider: Service = service
  }

  private lazy val loaders: Map[String, ProviderLoader] = {
    import scala.collection.JavaConverters._
    val loaders = ServiceLoader.load(classOf[ProviderLoader]).iterator.asScala.toList
    loaders.map(l => l.providerKey -> l).toMap
  }

  def find(config: AuthProvider): RIO[BaseEnv with Config, IdentityProvider.Service] = effectBlocking(loaders)
    .map(_.get(config.provider))
    .someOrFail(new NoSuchElementException(s"No identity provider could be found with key ${config.provider}"))
    .flatMap(_.provider(config.config))

  val load: RIO[BaseEnv with Config, IdentityProvider] =
    ZIO.access[Config](_.polynoteConfig.security.auth).get
      .foldM(_ => ZIO.succeed(none), config => find(config).map(of))

  def access: URIO[IdentityProvider, Service] = ZIO.access[IdentityProvider](_.identityProvider)

  def authRoutes: RIO[IdentityProvider, Routes] =
    ZIO.access[IdentityProvider](_.identityProvider.authRoutes.getOrElse(PartialFunction.empty))

  /**
    * Fail if the current user doesn't have the given permission
    */
  def checkPermission(permission: Permission): ZIO[BaseEnv with Config with UserIdentity with IdentityProvider, Permission.PermissionDenied, Unit] = for {
    service <- access
    ident   <- UserIdentity.access
    _       <- service.checkPermission(ident, permission)
  } yield ()

  /**
    * Provide a function that will authorize a Request against the configured auth provider
    */
  def authorize[Env <: BaseEnv with Config](implicit enrich: Enrich[Env, UserIdentity]): RIO[Env with IdentityProvider, (Request[Task], RIO[Env with UserIdentity, Response[Task]]) => RIO[Env, Response[Task]]] =
    access.map {
      provider =>
        (req, task) =>
          provider.checkAuth(req).foldM(
            ZIO.succeed,
            ident => task.provideSomeM(Env.enrich[Env][UserIdentity](UserIdentity.of(ident))))
    }
}

/**
  * A service interface for loading identity providers from plug-ins.
  */
trait ProviderLoader {
  def providerKey: String
  def provider(config: JsonObject): RIO[BaseEnv with Config, IdentityProvider.Service]
}

// An environment for user identity
trait UserIdentity {
  def userIdentity: Option[Identity]
}

object UserIdentity {

  def access: URIO[UserIdentity, Option[Identity]] = ZIO.access[UserIdentity](_.userIdentity)

  def of(maybeIdent: Option[Identity]): UserIdentity = new UserIdentity {
    val userIdentity: Option[Identity] = maybeIdent
  }

  final val empty: UserIdentity = of(None)

}