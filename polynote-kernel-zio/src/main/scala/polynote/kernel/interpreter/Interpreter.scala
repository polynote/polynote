package polynote.kernel.interpreter

import polynote.kernel.{Completion, CurrentRuntime, CurrentTask, ScalaCompiler, Signatures}
import zio.blocking.Blocking
import zio.{Task, TaskR}

trait Interpreter {

  /**
    * Run the given code in the given [[State]], returning an updated [[State]].
    *
    * @param code  The code string to be executed
    * @param state The given [[State]] will have the Cell ID of the cell containing the given code, and it will
    *              initially have empty values. Its `prev` will point to the [[State]] returned by the closes prior
    *              executed cell, or to [[State.Root]] if there is no such cell.
    */
  def run(code: String, state: State): TaskR[Blocking with PublishResults with CurrentTask with CurrentRuntime, State]

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
  def init(state: State): TaskR[Blocking with PublishResults with CurrentTask with CurrentRuntime, State]
}

object Interpreter {
  trait Factory {
    def languageName: String
    def apply(): TaskR[ScalaCompiler.Provider, Interpreter]
  }

  trait Factories {
    val interpreterFactories: Map[String, Interpreter.Factory]
  }
}