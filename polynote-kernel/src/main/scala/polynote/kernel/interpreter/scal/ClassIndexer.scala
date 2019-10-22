package polynote.kernel.interpreter.scal

import java.io.File
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import java.util.function.UnaryOperator

import io.github.classgraph.ClassGraph
import polynote.kernel.ScalaCompiler
import polynote.kernel.util.pathOf
import zio.blocking.{Blocking, effectBlocking}
import zio.{Fiber, RIO, UIO, ZIO}

import scala.collection.immutable.TreeMap

trait ClassIndexer {

  /**
    * Given a partial identifier, return matches of fully-qualified stable symbols which match it, grouped by their
    * unqualified name. Each matching fully-qualified symbol is paired with a priority number – lower means it is
    * "more likely" to be the desired match, for some measure of likelihood.
    */
  def findMatches(name: String): UIO[Map[String, List[(Int, String)]]]

}

object ClassIndexer {
  def default: ZIO[Blocking with ScalaCompiler.Provider, Nothing, ClassIndexer] =
    SimpleClassIndexer()
}

class SimpleClassIndexer(running: Fiber[Throwable, TreeMap[String, List[(Int, String)]]]) extends ClassIndexer {

  override def findMatches(name: String): UIO[Map[String, List[(Int, String)]]] = running.poll.map {
    case None => Map.empty
    case Some(finished) => finished.fold(_ => Map.empty, index => getRange(index, name))
  }

  def getRange(index: TreeMap[String, List[(Int, String)]], name: String): TreeMap[String, List[(Int, String)]] = {
    val result = index.range(name, name + Char.MaxValue)
    result
  }

}

object SimpleClassIndexer {
  def apply(): ZIO[Blocking with ScalaCompiler.Provider, Nothing, SimpleClassIndexer] = {
    def buildIndex(priorityDependencies: Array[File], classPath: Array[File]) = effectBlocking {
      import scala.collection.JavaConverters._

      val lastPriority = priorityDependencies.length + classPath.length
      val priorities = (priorityDependencies ++ classPath.diff(priorityDependencies)).distinct.zipWithIndex.toMap

      val classGraph = new ClassGraph().overrideClasspath(priorityDependencies ++ classPath: _*).enableClassInfo()
      val scanResult = classGraph.scan()
      val classes = new AtomicReference[TreeMap[String, List[(Int, String)]]](new TreeMap)
      scanResult.getAllClasses.iterator().asScala
        .filter(_.isPublic)
        .filterNot(_.isSynthetic)
        .filterNot(_.getSimpleName.contains("$"))
        .foreach {
          classInfo =>
            val priority = priorities.getOrElse(classInfo.getClasspathElementFile, lastPriority)
            classes.updateAndGet(new UnaryOperator[TreeMap[String, List[(Int, String)]]] {
              def apply(t: TreeMap[String, List[(Int, String)]]): TreeMap[String, List[(Int, String)]] =
                t + (classInfo.getSimpleName -> ((priority -> classInfo.getName) :: t.getOrElse(classInfo.getSimpleName, Nil)))
            })
        }
      classes.get()
    }

    def javaLibraryPath = Option(classOf[Object].getResource("Object.class")).flatMap {
      case url if url.getProtocol == "jar"  => try Some(new File(new URI(url.getPath.stripSuffix("!/java/lang/Object.class")))) catch { case err: Throwable => None }
      case url if url.getProtocol == "file" => try Some(new File(url.toURI)) catch { case err: Throwable => None }
      case _ => None
    }

    for {
      classPath <- ScalaCompiler.settings.map(_.classpath.value.split(File.pathSeparatorChar).map(new File(_)))
      deps      <- ScalaCompiler.dependencies
      javaPath   = javaLibraryPath.toArray :+ new File(pathOf(classOf[List[_]]).toURI)
      process   <- buildIndex(javaPath ++ deps, classPath).fork
    } yield new SimpleClassIndexer(process)
  }
}