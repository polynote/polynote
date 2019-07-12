package polynote.kernel.dependency

import java.io.File
import java.net.{URL, URLClassLoader}

import polynote.config.{DependencyConfigs, RepositoryConfig}
import polynote.kernel.{KernelStatusUpdate, TaskInfo}
import polynote.kernel.util.{LimitedSharingClassLoader, Publish}

import scala.reflect.ClassTag
import scala.reflect.internal.util.AbstractFileClassLoader
import scala.reflect.io.AbstractFile

/**
  * A [[DependencyManager]] handles the fetching and provision of dependencies.
  *
  * In this case, dependency *fetching* refers to bringing (e.g., downloading) the dependencies to this machine, and
  * dependency *providing* refers to making these dependencies available to the runtime (e.g., with a ClassLoader).
  *
  */
trait DependencyManager[F[_]] {
  // taskInfo to describe fetching the dependencies
  val taskInfo: TaskInfo

  // where to sent status updates
  val statusUpdates: Publish[F, KernelStatusUpdate]

  def getDependencyProvider(
    repositories: List[RepositoryConfig],
    dependencies: List[DependencyConfigs],
    exclusions: List[String]
  ): F[DependencyProvider]
}

trait DependencyManagerFactory[F[_]] {
  def apply(
    path: String,
    taskInfo: TaskInfo,
    statusUpdates: Publish[F, KernelStatusUpdate]
  ): DependencyManager[F]
}

trait DependencyProvider {
  val dependencies: List[(String, File)]

  def as[T <: DependencyProvider](implicit tag: ClassTag[T]): Either[Throwable, T] = this match {
    case d: T => Right(d)
    case other => Left(new IllegalArgumentException(s"A ${tag.toString()} was expected but found $other"))
  }
}

class ClassLoaderDependencyProvider(val dependencies: List[(String, File)]) extends DependencyProvider {

  def genNotebookClassLoader(
    extraClassPath: List[File],
    outputDir: AbstractFile,
    parentClassLoader: ClassLoader
  ): AbstractFileClassLoader = {

    def dependencyClassPath: Seq[URL] = dependencies.collect {
      case (_, file) if (file.getName endsWith ".jar") && file.exists() => file.toURI.toURL
    }

    /**
      * The class loader which loads the dependencies
      */
    val dependencyClassLoader: URLClassLoader = new LimitedSharingClassLoader(
      "^(scala|javax?|jdk|sun|com.sun|com.oracle|polynote|org.w3c|org.xml|org.omg|org.ietf|org.jcp|org.apache.spark|org.apache.hadoop|org.codehaus|org.slf4j|org.log4j)\\.",
      dependencyClassPath,
      parentClassLoader)

    /**
      * The class loader which loads the JVM-based notebook cell classes
      */
    new AbstractFileClassLoader(outputDir, dependencyClassLoader)
  }
}
