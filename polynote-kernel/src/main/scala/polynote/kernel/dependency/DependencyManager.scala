package polynote.kernel.dependency

import java.io.File
import java.net.{URL, URLClassLoader}

import polynote.config.{PolynoteConfig, RepositoryConfig}
import polynote.kernel.util.{LimitedSharingClassLoader, Publish}
import polynote.kernel.{KernelStatusUpdate, TaskInfo}

import scala.reflect.ClassTag
import scala.reflect.internal.util.AbstractFileClassLoader
import scala.reflect.io.AbstractFile

/**
  * A [[DependencyManager]] handles the fetching and setting up the provision of those dependencies (with a [[DependencyProvider]]).
  *
  * The idea is that when we want some dependencies to be available to our code, we typically need to do two things.
  * First, we need to get those dependencies (and _their_ dependencies, too) from somewhere and (usually) download them
  * as files onto our machine.
  * Second, we need to somehow link those dependencies on our filesystem with our running code.
  *
  * An example of the former would be something like Coursier which download jars from remote repos onto our machine
  * (in fact, we have a [[CoursierFetcher]]), while an example of the latter would be a classloader that provides the
  * classes in said jars to our code (e.g., [[ClassLoaderDependencyProvider]]).
  */
trait DependencyManager[F[_]] {
  // taskInfo to describe fetching the dependencies
  val taskInfo: TaskInfo

  // where to sent status updates
  val statusUpdates: Publish[F, KernelStatusUpdate]

  def getDependencyProvider(
    repositories: List[RepositoryConfig],
    dependencies: List[String],
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

/**
  * A [[DependencyProvider]] handles actually linking the fetched dependencies with the interpreter itself.
  *
  * The idea is similar to how a classloader works (indeed, we even have a [[ClassLoaderDependencyProvider]])
  *
  * @see [[DependencyManager]]
  */
trait DependencyProvider {
  val dependencies: List[(String, File)]

  def as[T <: DependencyProvider](implicit tag: ClassTag[T]): Either[Throwable, T] = this match {
    case d: T => Right(d)
    case other => Left(new IllegalArgumentException(s"A ${tag.toString()} was expected but found $other"))
  }
}

class ClassLoaderDependencyProvider(val dependencies: List[(String, File)]) extends DependencyProvider {

  def genNotebookClassLoader(
    config: PolynoteConfig,
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
    val dependencyClassLoader: URLClassLoader = if (config.behavior.dependencyIsolation) {
      new LimitedSharingClassLoader(
        "^(scala|javax?|jdk|sun|com.sun|com.oracle|polynote|org.w3c|org.xml|org.omg|org.ietf|org.jcp|org.apache.spark|org.apache.hadoop|org.codehaus|org.slf4j|org.log4j)\\.",
        dependencyClassPath,
        parentClassLoader)
    } else {
      new scala.reflect.internal.util.ScalaClassLoader.URLClassLoader(dependencyClassPath, parentClassLoader)
    }

    /**
      * The class loader which loads the JVM-based notebook cell classes
      */
    new AbstractFileClassLoader(outputDir, dependencyClassLoader)
  }
}
