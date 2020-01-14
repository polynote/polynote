package polynote.kernel

import java.net.URL

package object util {

  def pathOf(cls: Class[_]): URL = cls.getProtectionDomain.getCodeSource.getLocation

  // Since the environment is immutable, use this helper so tests can modify the "env" through system properties.
  def envOrProp(key: String, alternative: String): String = {
    sys.env.getOrElse(key, sys.props.getOrElse(key, alternative))
  }
}
