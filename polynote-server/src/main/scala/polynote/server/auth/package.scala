package polynote.server

import java.util.ServiceLoader

import polynote.config.AuthProvider
import polynote.kernel.BaseEnv
import polynote.kernel.environment.Config
import polynote.server.Server.Routes
import uzhttp.{HTTPError, Request, Response}
import zio.blocking.effectBlocking
import zio.{Has, RIO, Task, URIO, ZIO, ZLayer}

package object auth {
  type IdentityProvider = Has[IdentityProvider.Service]

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
      def checkAuth(req: Request): ZIO[BaseEnv with Config, Response, Option[Identity]]

      /**
        * Check whether the given identity (or None for anonymous) has the given permission. If not, the returned task
        * should fail with PermissionDenied.
        */
      def checkPermission(ident: Option[Identity], permission: Permission): ZIO[BaseEnv with Config, Permission.PermissionDenied, Unit]
    }

    val noneService: Service = new Service {
      def authRoutes: Option[Routes] = None
      def checkAuth(req: Request): ZIO[BaseEnv with Config, Response, Option[Identity]] = ZIO.succeed(None)
      def checkPermission(ident: Option[Identity], permission: Permission): ZIO[BaseEnv with Config, Permission.PermissionDenied, Unit] = ZIO.unit
    }

    def of(service: Service): IdentityProvider = Has(service)

    private lazy val loaders: Map[String, ProviderLoader] = {
      import scala.collection.JavaConverters._
      val loaders = ServiceLoader.load(classOf[ProviderLoader]).iterator.asScala.toList
      loaders.map(l => l.providerKey -> l).toMap
    }

    def find(config: AuthProvider): RIO[BaseEnv with Config, IdentityProvider.Service] = effectBlocking(loaders)
      .map(_.get(config.provider))
      .someOrFail(new NoSuchElementException(s"No identity provider could be found with key ${config.provider}"))
      .flatMap(_.provider(config.config))

    val load: RIO[BaseEnv with Config, IdentityProvider.Service] =
      ZIO.access[Config](_.get.security.auth).get
        .foldM(_ => ZIO.succeed(noneService), config => find(config))

    val layer: ZLayer[BaseEnv with Config, Throwable, IdentityProvider] = ZLayer.fromEffect(load)

    def access: URIO[IdentityProvider, Service] = ZIO.access[IdentityProvider](_.get)

    def authRoutes: URIO[IdentityProvider, Routes] =
      ZIO.access[IdentityProvider](_.get[IdentityProvider.Service].authRoutes.getOrElse(PartialFunction.empty))

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
    def authorize[Env <: BaseEnv with Config]: URIO[Env with IdentityProvider, (Request, ZIO[Env with UserIdentity, HTTPError, Response]) => ZIO[Env, HTTPError, Response]] =
      access.map {
        provider =>
          (req, task) =>
            provider.checkAuth(req).foldM(
              id => ZIO.succeed(id),
              ident => task.provideSomeLayer[Env](ZLayer.succeed(ident)))
      }
  }

  // An environment for user identity
  type UserIdentity = Has[Option[Identity]]
  object UserIdentity {
    def access: URIO[UserIdentity, Option[Identity]] = ZIO.access[UserIdentity](_.get)
    def of(maybeIdent: Option[Identity]): UserIdentity = Has(maybeIdent)
    final val empty: UserIdentity = of(None)
  }
}
