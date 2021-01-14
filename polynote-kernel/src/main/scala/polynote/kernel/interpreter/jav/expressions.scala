package polynote.kernel.interpreter.jav

import java.util.UUID
import java.util.regex.Pattern

package object expressions {

  sealed trait Expression {
    def source: String

    def key: String
  }

  ////

  trait ExpressionCompanion[T <: Expression] {
    val pattern: Pattern
    def matches(expression: String): Boolean = util.matchesPattern(pattern, expression)
  }

  ////

  final case class Assignment(source: String, key: String, value: String) extends Expression

  object Assignment extends ExpressionCompanion[Assignment] {
    val pattern: Pattern = Pattern.compile(
      "([a-zA-Z\\$_][a-zA-Z0-9\\$_]*) *=(.*)$",
      Pattern.DOTALL)


    override def matches(expression: String): Boolean = !expression.contains("==") && super.matches(expression)

    def extract(expression: String): Option[Assignment] = util.matchPattern(pattern, expression).map { mr =>
      Assignment(expression, mr.group(1), mr.group(2))
    }
  }

  ////

  final case class AssignmentWithType(source: String, typ: java.lang.reflect.Type, key: String, value: String) extends Expression

  object AssignmentWithType extends ExpressionCompanion[AssignmentWithType] {
    val pattern: Pattern = Pattern.compile(
      "([a-zA-Z\\$_][a-zA-Z0-9\\.\\$_\\[\\]<>,?& ]*) +([a-zA-Z\\$_][a-zA-Z0-9\\$_]*) *=(.*)$",
      Pattern.DOTALL)
  }

  ////

  final case class Import(source: String, typePackage: String) extends Expression {
    override def key: String = UUID.randomUUID().toString
  }

  object Import extends ExpressionCompanion[Import] {
    val pattern: Pattern = Pattern.compile(
      "import  *([^;^ ]*) *(?:;|).*",
      Pattern.DOTALL)

    def extract(expression: String): Option[Import] = util.matchPattern(pattern, expression).map { mr =>
      Import(expression, mr.group(1))
    }
  }

  ////

  final case class Method(source: String, typ: java.lang.reflect.Type, name: String, arguments: Seq[Class[_]]) extends Expression {
    override def key: String = name + arguments.map(_.getCanonicalName).mkString(", ")

    def signature: String = rendering.renderType(typ) + " (" + arguments.map(_.getCanonicalName).mkString(", ") + ")"
  }

  object Method extends ExpressionCompanion[Method] {
    val pattern: Pattern = Pattern.compile(
      "^(?!(?:new +))((?:(?:(?:(?:(?:final +))|(?:(?:static +)(?:final +))|(?:(?:private +|public +|protected +)(?:final +))|(?:(?:final +)(?:static +))|(?:(?:private +|public +|protected +)(?:static +)(?:final +))|(?:(?:final +)(?:private +|public +|protected +))|(?:(?:static +)(?:private +|public +|protected +)(?:final +))|(?:(?:private +|public +|protected +)(?:final +)(?:static +))|(?:(?:final +)(?:private +|public +|protected +)(?:static +))|(?:(?:static +)(?:final +)(?:private +|public +|protected +))|(?:(?:final +)(?:static +)(?:private +|public +|protected +))|(?:(?:static +))|(?:(?:private +|public +|protected +)(?:static +))|(?:(?:static +)(?:private +|public +|protected +))|(?:(?:private +|public +|protected +)))| *)|)([a-zA-Z\\$_][a-zA-Z0-9\\.\\$_\\[\\]<>,?& ]*) +([a-zA-Z\\$_][a-zA-Z0-9\\$_]*) *\\(.*?\\)) *\\{.*?",
      Pattern.DOTALL)
  }

  ////

  final case class Statement(source: String) extends Expression {
    override def key: String = UUID.randomUUID().toString
  }

  ////

  final case class Type(source: String, typePackage: Option[String], typ: String) extends Expression {
    override def key: String = typ

    def canonicalName: String = typePackage.map(_ + ".").getOrElse("") + typ
  }

  object Type extends ExpressionCompanion[Type] {
    val pattern: Pattern = Pattern.compile(
      "(?:.*package +([a-zA-Z0-9\\$_\\.]*) *;|).*?(?:(?:(?:(?:final +|abstract +))|(?:(?:static +)(?:final +|abstract +))|(?:(?:private +|public +|protected +)(?:final +|abstract +))|(?:(?:final +|abstract +)(?:static +))|(?:(?:private +|public +|protected +)(?:static +)(?:final +|abstract +))|(?:(?:final +|abstract +)(?:private +|public +|protected +))|(?:(?:static +)(?:private +|public +|protected +)(?:final +|abstract +))|(?:(?:private +|public +|protected +)(?:final +|abstract +)(?:static +))|(?:(?:final +|abstract +)(?:private +|public +|protected +)(?:static +))|(?:(?:static +)(?:final +|abstract +)(?:private +|public +|protected +))|(?:(?:final +|abstract +)(?:static +)(?:private +|public +|protected +))|(?:(?:static +))|(?:(?:private +|public +|protected +)(?:static +))|(?:(?:static +)(?:private +|public +|protected +))|(?:(?:private +|public +|protected +)))| *)(?:class +|interface +|enum +)([a-zA-Z\\$_][a-zA-Z0-9\\$_]*).*\\{.*",
      Pattern.DOTALL)

    def extract(expression: String): Option[Type] = util.matchPattern(pattern, expression).map { mr =>
      Type(expression, Option(mr.group(1)), mr.group(2))
    }
  }

  ////

  final case class Value(source: String) extends Expression {
    override def key: String = UUID.randomUUID().toString
  }
}
