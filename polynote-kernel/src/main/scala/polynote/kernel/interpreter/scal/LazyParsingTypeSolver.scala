package polynote.kernel.interpreter.scal

import com.github.javaparser.resolution.TypeSolver
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration
import com.github.javaparser.resolution.model.SymbolReference

class LazyParsingTypeSolver extends TypeSolver {

  override def getParent: TypeSolver = ???

  override def setParent(parent: TypeSolver): Unit = ???

  override def tryToSolveType(name: String): SymbolReference[ResolvedReferenceTypeDeclaration] = ???
}
