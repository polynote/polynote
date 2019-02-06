package polynote.kernel.lang

import java.io.File

import polynote.kernel._
import fs2.concurrent.{Enqueue, Queue}
import polynote.kernel.util.{Publish, RuntimeSymbolTable, SymbolDecl}

/**
  * The LanguageKernel runs code in a given language.
  */
trait LanguageKernel[F[_]] {

  // LanguageKernel is expected to have a reference to the shared runtime symbol table of a notebook
  val symbolTable: RuntimeSymbolTable

  final type Decl = SymbolDecl[F, symbolTable.global.type]

  def predefCode: Option[String]

  /**
    * Run the given code.
    *
    * @param cell           The identifier string of the cell for the code being run
    * @param visibleSymbols A list of symbols defined in cells "before" the given code, which are visible to it
    * @param previousCells  The identifier strings of the cells "before" this code, for stateful language kernels
    * @param code           The code string to run
    * @param out            A [[Queue]] which should receive [[Result]] value(s) describing the output(s) from running the code
    * @param statusUpdates  A [[Publish]] which should receive [[KernelStatusUpdate]]s describing changes in runtime status
    *                       from running the cell, such as newly defined symbols, subtask progress, etc
    */
  def runCode(
    cell: String,
    visibleSymbols: Seq[Decl],
    previousCells: Seq[String],
    code: String,
    out: Enqueue[F, Result],
    statusUpdates: Publish[F, KernelStatusUpdate]
  ): F[Unit]

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
    * Terminate the language kernel
    */
  def shutdown(): F[Unit]

}

object LanguageKernel {

  trait Factory[F[_]] {
    def apply(dependencies: List[(String, File)], symbolTable: RuntimeSymbolTable): LanguageKernel[F]
  }

}