package polynote.kernel.lang

import java.io.File
import java.util.ServiceLoader

import cats.effect.{ContextShift, IO}
import fs2.Stream
import fs2.concurrent.{Enqueue, Queue}
import polynote.kernel._
import polynote.kernel.dependency.{DependencyManagerFactory, DependencyProvider}
import polynote.kernel.util.{CellContext, KernelContext, Publish}
import polynote.messages.CellID

import scala.collection.JavaConverters._

/**
  * The LanguageInterpreter runs code in a given language.
  */
trait LanguageInterpreter[F[_]] {


  def predefCode: Option[String]

  /**
    * Run the given code.
    *
    * @param cellContext  An object describing things about the cell and its dependencies
    * @param code         The code string to run
    * @return An [[F]] that returns a [[Stream]] which will contain [[Result]] value(s) containing anything that
    *         resulted from running the code.
    */
  def runCode(
    cellContext: CellContext,
    code: String
  ): F[Stream[F, Result]]

  /**
    * Ask for completions (if applicable) at the given position in the given code string
    *
    * @param pos The position (character offset) within the code string at which completions are requested
    */
  def completionsAt(cellContext: CellContext, code: String, pos: Int): F[List[Completion]]

  /**
    * Ask for parameter signatures (if applicable) at the given position in the given code string
    *
    * @param pos The position (character offset) within the code string at which parameter hints are requested
    */
  def parametersAt(cellContext: CellContext, code: String, pos: Int): F[Option[Signatures]]

  /**
    * Initialize the kernel (if necessary)
    */
  def init(): F[Unit]

  /**
    * Terminate the language kernel
    */
  def shutdown(): F[Unit]

}

object LanguageInterpreter {

  trait Factory[F[_]] {
    def depManagerFactory: DependencyManagerFactory[F]
    def languageName: String
    def apply(kernelContext: KernelContext, dependencies: DependencyProvider)(implicit contextShift: ContextShift[F]): F[LanguageInterpreter[F]]
  }

  lazy val factories: Map[String, Factory[IO]] = ServiceLoader.load(classOf[LanguageInterpreterService]).iterator.asScala.toSeq
    .sortBy(_.priority)
    .foldLeft(Map.empty[String, LanguageInterpreter.Factory[IO]]) {
      (accum, next) => accum ++ next.interpreters
    }
}