package polynote.env.ops

import polynote.env.macros.LocationMacros

case class Location(
  file: String,
  line: Int,
  className: String,
  methodName: String
)

object Location {

  implicit def materialize: Location = macro LocationMacros.materialize

}