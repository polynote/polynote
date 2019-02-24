package polynote.kernel.lang

import java.io.File

import fs2.Stream
import fs2.concurrent.{Enqueue, Queue}
import polynote.kernel._
import polynote.kernel.util.{Publish, RuntimeSymbolTable, SymbolDecl}

/**
  * The LanguageKernel runs code in a given language.
  */
trait LanguageInterpreter[F[_]] {

  // LanguageKernel is expected to have a reference to the shared runtime symbol table of a notebook
  val symbolTable: RuntimeSymbolTable

  final type Decl = SymbolDecl[F]

  def predefCode: Option[String]

  /**
    * Run the given code.
    *
    * @param cell           The identifier string of the cell for the code being run
    * @param visibleSymbols A list of symbols defined in cells "before" the given code, which are visible to it
    * @param previousCells  The identifier strings of the cells "before" this code, for stateful language kernels
    * @param code           The code string to run
    * @return An [[F]] that returns a [[Stream]] which will contain [[Result]] value(s) containing anything that
    *         resulted from running the code.
    */
  def runCode(
    cell: String,
    visibleSymbols: Seq[Decl],
    previousCells: Seq[String],
    code: String
  ): F[Stream[F, Result]]

  /**
    * Ask for completions (if applicable) at the given position in the given code string
    *
    * @param pos The position (character offset) within the code string at which completions are requested
    */
  def completionsAt(cell: String, visibleSymbols: Seq[Decl], previousCells: Seq[String], code: String, pos: Int): F[List[Completion]]

  /**
    * Ask for parameter signatures (if applicable) at the given position in the given code string
    *
    * @param pos The position (character offset) within the code string at which parameter hints are requested
    */
  def parametersAt(cell: String, visibleSymbols: Seq[Decl], previousCells: Seq[String], code: String, pos: Int): F[Option[Signatures]]

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
    def languageName: String
    def apply(dependencies: List[(String, File)], symbolTable: RuntimeSymbolTable): LanguageInterpreter[F]
  }

}