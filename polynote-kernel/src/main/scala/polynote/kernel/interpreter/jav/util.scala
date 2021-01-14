package polynote.kernel.interpreter.jav

import java.io.File
import java.lang.reflect.{InvocationTargetException, Modifier, Type}
import java.net.{URL, URLClassLoader}
import java.util.Random
import java.util.jar.JarFile
import java.util.regex.{MatchResult, Pattern}

package object util {
  def create(file: File): File = {
    file.getParentFile.mkdirs()
    file.createNewFile()
    file
  }

  def directory(parent: File, name: String): File = {
    val child = new File(parent, name)
    child.mkdirs()
    child
  }

  def file(parent: File, name: String): File = {
    val file = new File(parent, name)
    if(file.isDirectory) {
      throw new IllegalArgumentException(s"${file} is a directory")
    }
    create(file)
    file
  }

  def extractType(typ: Type): Type = typ match {
    case clazz: Class[_] => {
      if(clazz.isAnonymousClass || clazz.isSynthetic || clazz.isMemberClass) {
        if(clazz.getGenericSuperclass.equals(classOf[Object])) {
          extractType(clazz.getGenericInterfaces.headOption.getOrElse(classOf[Object]))
        } else
          extractType(clazz.getGenericSuperclass)
      } else if(!Modifier.isPublic(clazz.getModifiers)) {
        extractType(clazz.getGenericSuperclass)
      } else {
        clazz
      }
    }
    case _ => typ
  }

  def unwrapException(e: Throwable): Throwable = e match {
    case ite: InvocationTargetException => unwrapException(ite.getTargetException)
    case _ => e
  }

  def javaVersionAtLeast(version: String): Boolean = System.getProperty("java.version").compareTo(version) >= 0

  private val chars: String = "abcdefghijklmnopqrstuvwxyz1234567890"
  private def randomChars(n: Int): String = {
    val rng = new Random()
    (0 until n).map(i => rng.nextInt(chars.size)).mkString("")
  }
  def randomIdentifier(prefix: String): String = prefix + "$" + randomChars(20)

  def getClassLoaderUrls(classLoader: ClassLoader): List[URL] = {
    if(classLoader == null) {
      List()
    } else if(classLoader.isInstanceOf[URLClassLoader]) {
      classLoader.asInstanceOf[URLClassLoader].getURLs.toList ++ getClassLoaderUrls(classLoader.getParent)
    } else {
      getClassLoaderUrls(classLoader.getParent)
    }
  }

  def recursiveFiles(file: File): Seq[File] = if(file.isDirectory) {
    file.listFiles().flatMap(recursiveFiles(_)) :+ file
  } else {
    Seq(file)
  }

  def entries(file: File): Seq[String] = {
    if(file.isDirectory) {
      recursiveFiles(file)
        .map(f => f.getPath.replace(file.getPath + File.separator, ""))
    } else {
      import scala.collection.JavaConverters._
      new JarFile(new File(file.toURI)).entries().asScala.map(_.getName).toSeq
    }
  }

  def matchPattern(pattern: Pattern, expression: String): Option[MatchResult] = {
    val matcher = pattern.matcher(expression)
    if(matcher.find()) {
      Some(matcher.toMatchResult)
    } else {
      None
    }
  }

  def matchesPattern(pattern: Pattern, s: String): Boolean = pattern.matcher(s.trim).matches()
}