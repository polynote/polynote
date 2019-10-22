package polynote.kernel
package interpreter

import java.util.ServiceLoader

import scala.collection.JavaConverters._

import cats.syntax.semigroup._
import cats.instances.map._
import cats.instances.list._
import polynote.messages.CellID
import polynote.kernel.environment.{Config, CurrentNotebook, CurrentTask, InterpreterEnvironment}
import zio.blocking.{Blocking, effectBlocking}
import zio.{Task, RIO, ZIO}

trait Interpreter {

  /**
    * Run the given code in the given [[State]], returning an updated [[State]].
    *
    * @param code  The code string to be executed
    * @param state The given [[State]] will have the Cell ID of the cell containing the given code, and it will
    *              initially have empty values. Its `prev` will point to the [[State]] returned by the closes prior
    *              executed cell, or to [[State.Root]] if there is no such cell.
    */
  def run(code: String, state: State): RIO[InterpreterEnv, State]

  /**
    * Ask for completions (if applicable) at the given position in the given code string.
    *
    * @param code  The code string in which completions are requested
    * @param pos   The position within the code string at which completions are requested
    * @param state The given [[State]] will have the Cell ID of the cell containing the given code, and it will
    *              initially have empty values. Its `prev` will point to the [[State]] returned by the closes prior
    *              executed cell, or to [[State.Root]] if there is no such cell.
    */
  def completionsAt(code: String, pos: Int, state: State): Task[List[Completion]]

  /**
    * Ask for parameter hints (if applicable) at the given position in the given code string.
    *
    * @param code  The code string in which parameter hints are requested
    * @param pos   The position within the code string at which parameter hints are requested
    * @param state The given [[State]] will have the Cell ID of the cell containing the given code, and it will
    *              initially have empty values. Its `prev` will point to the [[State]] returned by the closes prior
    *              executed cell, or to [[State.Root]] if there is no such cell.
    */
  def parametersAt(code: String, pos: Int, state: State): Task[Option[Signatures]]

  /**
    * Initialize the interpreter, running any predef code and setting up an initial state.
    * @param state A [[State]] which is the current state of the notebook execution.
    * @return An initial state for this interpreter
    */
  def init(state: State): RIO[InterpreterEnv, State]

  /**
    * Shut down this interpreter, releasing its resources and ending any internally managed tasks or processes
    */
  def shutdown(): Task[Unit]
}

object Interpreter {

  /**
    * An interpreter factory gets a Scala compiler and access to the current notebook, and constructs an interpreter.
    * It's also responsible for fetching/installing/configuring whatever dependencies are needed based on the
    * notebook configuration, or propagating any necessary notebook configuration state to the interpreter (as the
    * interpreter methods won't have access to the notebook)
    */
  trait Factory {
    def languageName: String
    def apply(): RIO[BaseEnv with GlobalEnv with ScalaCompiler.Provider with CurrentNotebook with CurrentTask with TaskManager, Interpreter]
    def requireSpark: Boolean = false
    def priority: Int = 0
  }

  trait Factories {
    val interpreterFactories: Map[String, List[Interpreter.Factory]]
  }

  def availableFactories(language: String): RIO[Factories, List[Interpreter.Factory]] = for {
    allFactories <- ZIO.access[Factories](_.interpreterFactories)
    factories    <- ZIO.fromOption(allFactories.get(language)).mapError(_ => new IllegalArgumentException(s"No interpreter for $language"))
  } yield factories

}

trait Loader {
  def factories: Map[String, Interpreter.Factory]
}

object Loader {
  def load: RIO[Blocking, Map[String, List[Interpreter.Factory]]] = effectBlocking(ServiceLoader.load(classOf[Loader]).iterator.asScala.toList).map {
    loaders =>
      loaders.map(_.factories.mapValues(List(_))).foldLeft(Map.empty[String, List[Interpreter.Factory]])(_ |+| _).mapValues(_.sortBy(f => (-f.priority, !f.getClass.getName.startsWith("polynote"), f.getClass.getName)))
  }
}