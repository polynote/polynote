package polynote.kernel.interpreter.scal

import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration
import com.github.javaparser.resolution.model.SymbolReference
import com.github.javaparser.resolution.{Navigator, TypeSolver}
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade

import java.util.concurrent.ConcurrentHashMap
import scala.tools.nsc.interactive

class LazyParsingTypeSolver(
  semanticDbScan: SemanticDbScan,
  underlying: TypeSolver
) extends TypeSolver {

  private val cache = new ConcurrentHashMap[String, ResolvedReferenceTypeDeclaration]()
  private var parent: Option[TypeSolver] = None

  override def getParent: TypeSolver = parent.orNull
  override def setParent(parent: TypeSolver): Unit = this.parent = Some(parent)

  override def tryToSolveType(name: String): SymbolReference[ResolvedReferenceTypeDeclaration] =
    if (cache.containsKey(name)) {
      val cached = cache.get(name)
      if (cached != null) {
        SymbolReference.solved(cached)
      } else SymbolReference.unsolved()
    } else {
      val solved = impl(name)
      if (solved.isSolved) {
        cache.putIfAbsent(name, solved.getCorrespondingDeclaration)
        solved
      } else SymbolReference.unsolved()
    }

  private def impl(name: String): SymbolReference[ResolvedReferenceTypeDeclaration] = {
    val outerClassName = name.indexOf('$') match {
      case -1 => name
      case n => name.substring(0, n)
    }
    if (semanticDbScan.javaMapping.containsKey(outerClassName)) {
      try {
        val unit = semanticDbScan.javaMapping.get(outerClassName)
        val content = new String(unit.source.content)
        val parseResult = semanticDbScan.javaParser.parse(content)
        val parsed = parseResult.getResult.get
        parsed.setData(SemanticDbScan.OriginalSource, unit.source.file)
        val pkgFromPath = unit.source.path.split('!').last.split('/').dropRight(1).mkString(".")
        val foundType = Navigator.findType(parsed, name.stripPrefix(pkgFromPath).stripPrefix("."))
        if (foundType.isPresent) {
          SymbolReference.solved(JavaParserFacade.get(this).getTypeDeclaration(foundType.get()))
        } else SymbolReference.unsolved()
      } catch {
        case _: Throwable => SymbolReference.unsolved()
      }
    } else {
      underlying.tryToSolveType(name)
    }
  }

}
