package polynote.kernel.interpreter.scal

import com.github.javaparser.ast.`type`.{ArrayType, ClassOrInterfaceType}
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.expr._
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt
import com.github.javaparser.ast.visitor.GenericVisitorAdapter
import com.github.javaparser.ast.{CompilationUnit, Node}
import com.github.javaparser.resolution.Resolvable
import com.github.javaparser.resolution.declarations.AssociableToAST
import com.github.javaparser.resolution.types.{ResolvedReferenceType, ResolvedType}
import com.github.javaparser.{Position => JPosition}

import java.util.Optional
import scala.reflect.io.AbstractFile

class JavaTreeFinder extends GenericVisitorAdapter[AbstractFile, JPosition] {
  // unfortunately this has to return null instead of option in order for the super methods to function – they rely on
  // non-null to signal success

  override def visit(n: Parameter, arg: JPosition): AbstractFile =
    Option(super.visit(n, arg)).orElse(associable(n, arg)).orNull

  override def visit(n: ClassOrInterfaceType, arg: JPosition): AbstractFile = {
    if (!n.getRange.asScala.exists(_.contains(arg))) {
      return null
    }
    val result1 = super.visit(n, arg)
    if (result1 != null) {
      return result1
    }
    val resolved = n.resolve()
    val decl = resolved.asReferenceType().getTypeDeclaration.asScala
    decl.flatMap {
      decl =>
        val ast = decl.toAst().asScala
        ast.map {
          node =>
            var n = node
            while (!n.isInstanceOf[CompilationUnit]) {
              n = n.getParentNode.get()
            }
            n.getData(SemanticDbScan.OriginalSource)
        }
    }.orNull
  }

  override def visit(n: ArrayType, arg: JPosition): AbstractFile =
    Option(super.visit(n, arg)).orElse(refType(n, arg)).orNull

  override def visit(n: FieldAccessExpr, arg: JPosition): AbstractFile =
    Option(super.visit(n, arg)).orElse(associable(n, arg)).orNull

  override def visit(n: MethodCallExpr, arg: JPosition): AbstractFile =
    Option(super.visit(n, arg)).orElse(associable(n, arg)).orNull

  override def visit(n: NameExpr, arg: JPosition): AbstractFile =
    Option(super.visit(n, arg)).orElse(associable(n, arg)).orNull

  override def visit(n: ObjectCreationExpr, arg: JPosition): AbstractFile =
    Option(super.visit(n, arg)).orElse(associable(n, arg)).orNull

  override def visit(n: ThisExpr, arg: JPosition): AbstractFile =
    Option(super.visit(n, arg)).orElse(associable(n, arg)).orNull

  override def visit(n: MarkerAnnotationExpr, arg: JPosition): AbstractFile =
    Option(super.visit(n, arg)).orElse(associable(n, arg)).orNull

  override def visit(n: SingleMemberAnnotationExpr, arg: JPosition): AbstractFile =
    Option(super.visit(n, arg)).orElse(associable(n, arg)).orNull

  override def visit(n: NormalAnnotationExpr, arg: JPosition): AbstractFile =
    Option(super.visit(n, arg)).orElse(associable(n, arg)).orNull

  override def visit(n: ExplicitConstructorInvocationStmt, arg: JPosition): AbstractFile =
    Option(super.visit(n, arg)).orElse(associable(n, arg)).orNull

  override def visit(n: MethodReferenceExpr, arg: JPosition): AbstractFile =
    Option(super.visit(n, arg)).orElse(associable(n, arg)).orNull

  private def impl1[T](node: Node with Resolvable[T], pos: JPosition)(fn: T => Option[Node]): Option[AbstractFile] =
    node.getRange.asScala.filter(_.contains(pos)).flatMap {
      _ => try {
        val resolved = node.resolve()
        fn(resolved).flatMap(getPath)
      } catch {
        case err: Throwable =>
          throw err
          None
      }
    }

  private def refType[T <: ResolvedType](node: Node with Resolvable[T], pos: JPosition) = impl1(node, pos) {
    case ref: ResolvedReferenceType => ref.getTypeDeclaration.asScala.flatMap(_.toAst.asScala) // it should be a ResolvedReferenceType
    case _ => None
  }

  private def associable[T <: AssociableToAST](node: Node with Resolvable[T], pos: JPosition): Option[AbstractFile] =
    impl1[T](node, pos)(_.toAst.asScala)

  private def getPath(node: Node): Option[AbstractFile] = node match {
    case node: CompilationUnit => Option(node.getData(SemanticDbScan.OriginalSource))
    case node => node.getParentNode.asScala.flatMap(getPath)
  }

  private def attempt[N <: Node](n: N, pos: JPosition)(fn: N => Option[AbstractFile]): Option[AbstractFile] =
    if (n.getRange.isPresent && n.getRange.get.contains(pos)) {
      try fn(n) catch {
        case err: Throwable => None
      }
    } else None

  implicit class OptionalInterop[T <: AnyRef](private val self: Optional[T]) {
    def asScala: Option[T] = if (self.isPresent) Option(self.orElse(null.asInstanceOf[T])) else None
  }
}
