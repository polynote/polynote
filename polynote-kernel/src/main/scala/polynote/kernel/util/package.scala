package polynote.kernel

import java.net.URL

import scala.reflect.api.Universe

package object util {

  type Importer[To <: Universe, From <: Universe] = To#Importer { val from: From }

  def pathOf(cls: Class[_]): URL = cls.getProtectionDomain.getCodeSource.getLocation

}
