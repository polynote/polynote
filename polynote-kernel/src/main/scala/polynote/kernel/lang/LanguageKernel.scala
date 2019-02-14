package polynote.kernel.lang

import java.io.File

import fs2.Stream
import polynote.kernel._
import polynote.kernel.context.{RuntimeContext, SymbolDecl}
import polynote.kernel.util.{Publish, RuntimeSymbolTable}

/**
  * The LanguageKernel runs code in a given language.
  */
trait LanguageKernel[F[_]] {

  // LanguageKernel is expected to have a reference to the shared runtime symbol table of a notebook
  val runtimeContext: RuntimeContext

  final type Decl = SymbolDecl[F, runtimeContext.globalInfo.global.type]

  /**
    * Run the given code.
    *
    * @param cell                The identifier string of the cell for the code being run
    * @param runtimeContextView  A View into the runtime context for this particular cell
    * @param code                The code string to run
    * @return                    A Tuple of Streams: (results, new runtime symbols, maybe a cell return value)
    */
  def runCode(
    cell: String,
    runtimeContextView: runtimeContext.RuntimeContextView, // TODO: this could just be the parent cell id...
    code: String
  ): F[(Stream[F, Result], runtimeContext.RuntimeContextEntry)]

  /**
    * Ask for completions (if applicable) at the given position in the given code string
    *
    * @param pos The position (character offset) within the code string at which completions are requested
    */
  def completionsAt(cell: String, runtimeContextView: runtimeContext.RuntimeContextView, code: String, pos: Int): F[List[Completion]]

  /**
    * Ask for parameter signatures (if applicable) at the given position in the given code string
    *
    * @param pos The position (character offset) within the code string at which parameter hints are requested
    */
  def parametersAt(cell: String, runtimeContextView: runtimeContext.RuntimeContextView, code: String, pos: Int): F[Option[Signatures]]

  /**
    * Terminate the language kernel
    */
  def shutdown(): F[Unit]

}

object LanguageKernel {

  trait Factory[F[_]] {
    def languageName: String
    def apply(dependencies: List[(String, File)], runtimeContext: RuntimeContext): LanguageKernel[F]
  }

}