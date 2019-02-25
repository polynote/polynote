package polynote

import io.circe.generic.extras.Configuration

package object server {
  implicit val circeConfig: Configuration =
    Configuration.default.withSnakeCaseConstructorNames.withSnakeCaseMemberNames.withDefaults
}
