package polynote.kernel.lang

import scala.reflect.api.Universe

/**
  * Package is called "scal", because "scala" would clash with _root_.scala.
  */
package object scal {

  type Importer[To <: Universe, From <: Universe] = To#Importer { val from: From }

}
