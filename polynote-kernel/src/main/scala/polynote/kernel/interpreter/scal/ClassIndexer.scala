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

  def await: UIO[Unit]
}

object ClassIndexer {
  def default: ZIO[Blocking with ScalaCompiler.Provider, Nothing, ClassIndexer] =
    SimpleClassIndexer()
}

class SimpleClassIndexer(ref: AtomicReference[TreeMap[String, List[(Int, String)]]], process: Fiber[Throwable, Any]) extends ClassIndexer {

  override def findMatches(name: String): UIO[Map[String, List[(Int, String)]]] =
    ZIO.effectTotal(ref.get).map(_.range(name, name + Char.MaxValue))

  override def await: UIO[Unit] = process.await.unit
}

object SimpleClassIndexer {
  def apply(): ZIO[Blocking with ScalaCompiler.Provider, Nothing, SimpleClassIndexer] = {
    def buildIndex(
      priorityDependencies: Array[File],
      classPath: Array[File],
      classes: AtomicReference[TreeMap[String, List[(Int, String)]]]
    ) = effectBlocking {
      import scala.collection.JavaConverters._

      val lastPriority = priorityDependencies.length + classPath.length
      val priorities = (priorityDependencies ++ classPath.diff(priorityDependencies)).distinct.zipWithIndex.toMap

      val classGraph = new ClassGraph().overrideClasspath(priorityDependencies ++ classPath: _*).enableClassInfo()
      val scanResult = classGraph.scan()
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
      priorities = new File(pathOf(classOf[List[_]]).toURI) :: javaLibraryPath.toList ::: deps
      indexRef   = new AtomicReference[TreeMap[String, List[(Int, String)]]](new TreeMap)
      process   <- buildIndex(priorities.toArray, classPath, indexRef).forkDaemon
    } yield new SimpleClassIndexer(indexRef, process)
  }
}