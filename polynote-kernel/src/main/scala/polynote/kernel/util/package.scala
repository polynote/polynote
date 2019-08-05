package polynote.kernel

import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ExecutorService, Executors, ThreadFactory}

import scodec.Codec
import scodec.codecs.DiscriminatorCodec
import shapeless.HList

import scala.reflect.api.Universe

package object util {

  type Importer[To <: Universe, From <: Universe] = To#Importer { val from: From }

  def pathOf(cls: Class[_]): URL = cls.getProtectionDomain.getCodeSource.getLocation

  class DaemonThreadFactory(name: String, contextClassLoader: Option[ClassLoader]) extends ThreadFactory {
    private val threadGroup: ThreadGroup = new ThreadGroup(name)
    private val threadCounter = new AtomicInteger(0)
    def newThread(r: Runnable): Thread = {
      val t = new Thread(
        threadGroup, r, s"$name-${java.lang.Integer.toUnsignedString(threadCounter.getAndIncrement())}"
      )
      contextClassLoader.foreach(t.setContextClassLoader)
      t.setDaemon(true)
      t
    }
  }

  def newDaemonThreadPool(name: String, contextClassLoader: Option[ClassLoader] = None): ExecutorService =
    Executors.newCachedThreadPool(new DaemonThreadFactory(name, contextClassLoader))
}
