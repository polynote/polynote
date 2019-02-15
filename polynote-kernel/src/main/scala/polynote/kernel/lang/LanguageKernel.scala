package polynote.kernel.lang

import java.io.File

import fs2.Stream
import polynote.kernel._
import polynote.kernel.context.{GlobalInfo, RuntimeContext, SymbolDecl}

/**
  * The LanguageKernel runs code in a given language.
  */
trait LanguageKernel[F[_], G <: GlobalInfo] {

  /**
    * Run the given code.
    *
    * @param cell            The identifier string of the cell for the code being run
    * @param runtimeContext  A View into the runtime context for this particular cell
    * @param code            The code string to run
    * @return                A Tuple of Streams: (results, new runtime symbols, maybe a cell return value)
    */
  def runCode(
    cell: String,
    runtimeContext: RuntimeContext[G], // TODO: this could just be the parent cell id...
    code: String
  ): F[(Stream[F, Result], F[RuntimeContext[G]])]

  /**
    * Ask for completions (if applicable) at the given position in the given code string
    *
    * @param pos The position (character offset) within the code string at which completions are requested
    */
  def completionsAt(cell: String, runtimeContext: RuntimeContext[G], code: String, pos: Int): F[List[Completion]]

  /**
    * Ask for parameter signatures (if applicable) at the given position in the given code string
    *
    * @param pos The position (character offset) within the code string at which parameter hints are requested
    */
  def parametersAt(cell: String, runtimeContext: RuntimeContext[G], code: String, pos: Int): F[Option[Signatures]]

  /**
    * Terminate the language kernel
    */
  def shutdown(): F[Unit]

}

object LanguageKernel {

  trait Factory[F[_], G <: GlobalInfo] {
    def languageName: String
    def apply(dependencies: List[(String, File)], globalInfo: G): LanguageKernel[F, G]
  }

}