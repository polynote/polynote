package polynote.runtime.macros

import java.nio.charset.StandardCharsets

import polynote.runtime.{DataEncoder, DataType, StringType, StructField, StructType}

import scala.reflect.macros.whitebox
import polynote.runtime.DataEncoder.StructDataEncoder
import shapeless.CaseClassMacros

class StructDataEncoderMacros(val c: whitebox.Context) extends CaseClassMacros {

  import c.universe._

  private val DataEncoderTC = weakTypeOf[DataEncoder[_]].typeConstructor

  /**
    * A macro to materialize a more performant set of getters for [[StructDataEncoder]] over a case class.
    */
  def materialize[A <: Product : WeakTypeTag]: Expr[StructDataEncoder[A]] = {
    val A = weakTypeOf[A].dealias.widen
    fieldsOf(A) match {
      case Nil => c.abort(c.enclosingPosition, "Not a case class, or has no fields")
      case fields =>
        fields.flatMap {
          case (name, typ) =>
            c.inferImplicitValue(appliedType(DataEncoderTC, typ)) match {
              case EmptyTree =>
                val stringDE = q"polynote.runtime.DataEncoder.unknownDataEncoder[$typ](${typ.toString})"
                Some((name, typ, stringDE))
              case tree =>
                Some((name, typ, tree))
            }
        } match {
          case Nil => c.abort(c.enclosingPosition, "No fields with available DataEncoders")
          case encodableFields =>
            val (fields, encoderVariables, structFields) = encodableFields.map {
              case (name, typ, tree) =>
                val nameStr = name.decodedName.toString
                val nameStrExpr = c.Expr[String](Literal(Constant(nameStr)))
                val encoderVariableName = c.freshName(name)
                val encoderVariable = ValDef(Modifiers(), encoderVariableName, TypeTree(appliedType(DataEncoderTC, typ)), tree)
                val dataTypeExpr = c.Expr[DataType](Select(Ident(encoderVariableName), TermName("dataType")))

                val structField = reify {
                  StructField(nameStrExpr.splice, dataTypeExpr.splice)
                }

                ((name, typ), encoderVariable, structField)
            }.unzip3

            val structFieldsList = c.Expr[List[StructField]](q"List(..$structFields)")
            val structType = reify(StructType(structFieldsList.splice))

            val (encodeFields, sizeFields) = fields.zip(encoderVariables).map {
              case ((name, typ), encoderVariable) =>
                val encode = q"${encoderVariable.name}.encode(output, value.$name)"
                val size = q"${encoderVariable.name}.sizeOf(value.$name)"

                (encode, size)
            }.unzip

            val sizeOf = sizeFields.reduceRight {
              (nextSize, totalSize) => q"polynote.runtime.DataEncoder.combineSize($nextSize, $totalSize)"
            }

            val matchFields = fields.zip(encoderVariables).map {
              case ((name, typ), encoderVariable) =>
                cq"${name.decodedName.toString} => Some((((value: $A) => value.$name), ${encoderVariable.name}))"
            } :+ cq"_ => None"

            val result = q"""
              ..$encoderVariables
              new _root_.polynote.runtime.DataEncoder.StructDataEncoder[$A]($structType) {
                def encode(output: java.io.DataOutput, value: $A): Unit = { ..$encodeFields }
                def sizeOf(value: $A): _root_.scala.Int = $sizeOf
                def field(name: String): Option[($A => Any, _root_.polynote.runtime.DataEncoder[_])] = name match { case ..$matchFields }
              }
            """


            c.Expr[StructDataEncoder[A]](result)
        }
    }

  }

}
