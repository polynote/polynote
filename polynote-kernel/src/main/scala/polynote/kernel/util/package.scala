package polynote.kernel

import java.net.URL

package object util {

  def pathOf(cls: Class[_]): URL = cls.getProtectionDomain.getCodeSource.getLocation

}
