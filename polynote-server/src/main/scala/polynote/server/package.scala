package polynote

import polynote.kernel.{BaseEnv, GlobalEnv}
import polynote.server.auth.{IdentityProvider, UserIdentity}

package object server {
  type SessionEnv = BaseEnv with GlobalEnv with UserIdentity with IdentityProvider
}
