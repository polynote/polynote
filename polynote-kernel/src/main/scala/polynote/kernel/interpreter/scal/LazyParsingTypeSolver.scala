package polynote.kernel.interpreter.scal

import com.github.javaparser.{JavaParser, StaticJavaParser}
import com.github.javaparser.resolution.{Navigator, TypeSolver}
import com.github.javaparser.resolution.declarations.{ResolvedReferenceTypeDeclaration, ResolvedTypeDeclaration}
import com.github.javaparser.resolution.model.SymbolReference
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade

import java.io.File
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import scala.reflect.io.ZipArchive
import scala.tools.nsc.interactive
import scala.collection.JavaConverters._

class LazyParsingTypeSolver[Global <: interactive.Global](
  val global: Global,
  semanticDbScan: SemanticDbScan,
  units: ConcurrentHashMap[String, Global#RichCompilationUnit],
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
    if (units.containsKey(outerClassName)) {
      try {
        val unit = units.get(outerClassName)
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
        case err: Throwable => SymbolReference.unsolved()
      }
    } else {
      underlying.tryToSolveType(name)
    }
  }

}
