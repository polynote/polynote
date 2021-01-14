package polynote.kernel.interpreter.jav

import java.lang.reflect.{ParameterizedType, Type, TypeVariable}
import java.util.{Arrays => JArrays}

import polynote.kernel.interpreter.jav.eval.EvaluationContext
import polynote.kernel.interpreter.jav.expressions.{Assignment, AssignmentWithType, Expression, Import, Method, Statement, Value}

package object rendering {
  def renderType(typ: Type): String = {
    util.extractType(typ) match {
      case c: Class[_] => c.getCanonicalName
      case _: TypeVariable[_] => "Object"
      case pt: ParameterizedType => String.format(
        "%s<%s>",
        renderType(pt.getRawType),
        pt.getActualTypeArguments.map(renderType).mkString(", ")
      )
      case e => e.toString
    }
  }

  def renderValue(value: Object): String = value match {
    case s: String => "\"" + s + "\"" // TODO escape?
    case as: Array[Object] => "[" + as.map(renderValue).mkString(",") + "]"
    case as: Array[Boolean] => JArrays.toString(as)
    case as: Array[Byte] => JArrays.toString(as)
    case as: Array[Char] => JArrays.toString(as)
    case as: Array[Short] => JArrays.toString(as)
    case as: Array[Int] => JArrays.toString(as)
    case as: Array[Long] => JArrays.toString(as)
    case as: Array[Float] => JArrays.toString(as)
    case as: Array[Double] => JArrays.toString(as)
    case _ => String.valueOf(value)
  }

  def renderMethodName(expression: Expression): String = {
    def methodNameWithType(returnType: Type): String = String.format("  public %s evaluate() throws Exception {", renderType(returnType))

    val voidClass = java.lang.Void.TYPE // void.class
    val objClass = classOf[Object] // Object.class

    expression match {
      case method: Method => methodNameWithType(voidClass)
      case value: Value => methodNameWithType(objClass)
      case assignment: Assignment => methodNameWithType(objClass)
      case impor: Import => methodNameWithType(voidClass)
      case typ: expressions.Type => throw new IllegalArgumentException(typ + " not mapped")
      case statement: Statement => methodNameWithType(voidClass)
      case assignmentWithType: AssignmentWithType => methodNameWithType(assignmentWithType.typ)
    }
  }

  val EXPRESSION_TOKEN = "$JAVAREPL_EXPRESSION_TOKEN$"
  val EXPRESSION_VALUE = "$JAVAREPL_EXPRESSION_VALUE$"

  private def expressionWithValue(str: String, returnType: String): String = String.format(
    "    %s %s =\n\n    %s;\n\n    return %s;",
    returnType,
    EXPRESSION_VALUE,
    EXPRESSION_TOKEN,
    EXPRESSION_VALUE)
  private def expressionWithValue(value: String, returnType: Type): String = expressionWithValue(value, renderType(returnType))
  private def expressionWithValue(value: String): String = expressionWithValue(value, classOf[Object])

  def renderExpressionToken(expression: Expression): String = expression match {
    case method: Method => " " + EXPRESSION_TOKEN + "\n"
    case value: Value => expressionWithValue(value.source)
    case assignment: Assignment => expressionWithValue(assignment.value)
    case impor: Import => EXPRESSION_TOKEN + ";"
    case typ: expressions.Type => throw new IllegalArgumentException(typ + " not mapped")
    case statement: Statement => "    " + EXPRESSION_TOKEN + ";"
    case assignmentWithType: AssignmentWithType => expressionWithValue(assignmentWithType.value, assignmentWithType.typ)
  }

  def renderExpressionSource(expression: Expression): String = expression match {
    case method: Method => method.source.replaceAll("\n", "\n  ")
    case value: Value => value.source
    case assignment: Assignment => assignment.value
    case impor: Import => impor.source
    case typ: expressions.Type => typ.source
    case statement: Statement => statement.source
    case assignmentWithType: AssignmentWithType => assignmentWithType.value
  }

  private case class EvaluationClassRenderer(context: EvaluationContext, className: String) {
    def apply(expression: Expression): String = expression match {
      case m: Method => renderFragments(
        renderPreviousImports(),
        renderClassName(),
        renderClassConstructor(),
        renderPreviousEvaluations(),
        renderPreviousMethods(),
        renderExpressionToken(expression),
        renderMethodName(expression),
        renderEndOfMethod(),
        renderEndOfFile()
      )
      case v: Value => render(v)
      case a: Assignment => render(a)
      case i: Import => renderFragments(
        renderPreviousImports(),
        renderExpressionToken(expression),
        renderClassName(),
        renderClassConstructor(),
        renderPreviousEvaluations(),
        renderMethodName(expression),
        renderEndOfMethod(),
        renderEndOfFile()
      )
      case t: expressions.Type => renderFragments(
        t.typePackage.map(tp => "").getOrElse(renderPreviousImports()),
        t.source
      )
      case s: Statement => render(s)
      case awt: AssignmentWithType => render(awt)
    }


    def renderPreviousEvaluations(): String =
      context.results.map { result =>
        String.format("  public %s %s = valueOf(\"%s\");", renderType(result.resolveType), result.key, result.key)
      } mkString("\n")

    def renderPreviousMethods(): String =
      context.expressions.flatMap { e =>
        e match {
          case m: Method => List(m);
          case _ => List()
        }
      } map { m =>
        String.format("  %s", m.source.replaceAll("\n", "\n  "))
      } mkString("\n\n")

    def renderPreviousImports(): String =
      context.expressions.flatMap { e =>
        e match {
          case i: Import => List(i);
          case _ => List()
        }
      } map { i =>
        String.format("%s;", i.source)
      } mkString("\n")

    def renderClassName(): String = String.format(
      "public final class %s extends %s {",
      className,
      classOf[EvaluationTemplate].getCanonicalName
    )

    def renderClassConstructor(): String = String.format(
      "  public %s(%s context) { super(context); }",
      className,
      classOf[EvaluationLookup].getCanonicalName
    )

    def renderEndOfMethod(): String = "  }"
    def renderEndOfFile(): String = "}"
    def renderFragments(elements: String*): String = elements.filter(p => !p.trim.isEmpty).mkString("\n")

    ////

    def renderMethodSignatureDetection(expression: String): String =
      renderFragments(
        renderPreviousImports(),
        renderInterfaceName(),
        renderInterfaceMethod(expression),
        renderEndOfFile()
      )

    def renderInterfaceName(): String = String.format("public interface %s {", className)

    def renderInterfaceMethod(expression: String): String = {
      val signature = util.matchPattern(Method.pattern, expression).get.group(1)
      val typ = util.matchPattern(Method.pattern, expression).get.group(2)
      String.format("  %s;", signature.substring(signature.indexOf(typ)))
    }

    private def render(expression: Expression): String =
      renderFragments(
        renderPreviousImports(),
        renderClassName(),
        renderClassConstructor(),
        renderPreviousEvaluations(),
        renderPreviousMethods(),
        renderMethodName(expression),
        renderExpressionToken(expression),
        renderEndOfMethod(),
        renderEndOfFile()
      )
  }

  def renderExpressionClass(context: EvaluationContext, className: String, expression: Expression): String =
    EvaluationClassRenderer(context, className).apply(expression)

  def renderMethodSignatureDetection(context: EvaluationContext, className: String, expression: String): String =
    EvaluationClassRenderer(context, className).renderMethodSignatureDetection(expression)

  ////
}
