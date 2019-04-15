package polynote.kernel.util

import java.net.URL
import java.util.regex.Pattern

/**
  * A [[ClassLoader]] that delegates only certain classes to the parent, based on a regular expression. This is useful
  * for creating an isolated class loader which shares some necessary classes (i.e. java and scala libraries) with the
  * root class loader, so that instances of those classes can be freely used by isolated classes.
  *
  * Resource loading behavior is not changed from a standard class loader (TODO?)
  *
  * @param shareRegex Regular expression matching which classes to share. Any class whose full name matches this expression
  *                   will be delegated to the parent.
  * @param urls       URLs representing the classpath
  * @param parent     The parent ClassLoader
  */
// TODO: should resource loading be similarly altered?
class LimitedSharingClassLoader(
  shareRegex: String,
  urls: Seq[URL],
  parent: ClassLoader
) extends scala.reflect.internal.util.ScalaClassLoader.URLClassLoader(urls, parent) {

  private val share = Pattern.compile(shareRegex).asPredicate()

  override def loadClass(name: String, resolve: Boolean): Class[_] =  getClassLoadingLock(name).synchronized {
    val c = if (share.test(name)) {
      //System.err.println(s"Delegating class $name")
      try {
        super.loadClass(name, resolve)
      } catch {
        case err: ClassNotFoundException => findClass(name)
      }
    } else try {
      findLoadedClass(name) match {
        case null =>
          //System.err.println(s"Privately loading class $name")
          findClass(name)
        case lc => lc
      }
    } catch {
      case _: ClassNotFoundException | _: LinkageError =>
        super.loadClass(name, resolve)
    }

    if (resolve) {
      resolveClass(c)
    }

    c
  }


}