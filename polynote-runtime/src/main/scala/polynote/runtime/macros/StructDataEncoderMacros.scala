package polynote.runtime.macros

import polynote.runtime.{DataEncoder, DataType, StructField, StructType}

import scala.reflect.macros.whitebox
import polynote.runtime.DataEncoder.StructDataEncoder

class StructDataEncoderMacros(val c: whitebox.Context) {

  import c.universe._

  private val DataEncoderTC = weakTypeOf[DataEncoder[_]].typeConstructor

  ////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // The following methods were transplanted from shapeless CaseClassMacros:                                                //
  //   https://github.com/milessabin/shapeless/blob/master/core/src/main/scala/shapeless/generic.scala#L275 //                                                                          //
  // in order to avoid a 5MB dependency. They are used under the Apache license:                            //
  //   https://github.com/milessabin/shapeless/blob/master/LICENSE                                          //
  ////////////////////////////////////////////////////////////////////////////////////////////////////////////

  private def isAnonOrRefinement(sym: Symbol): Boolean = {
    val nameStr = sym.name.toString
    nameStr.contains("$anon") || nameStr == "<refinement>"
  }

  private def isCaseAccessorLike(sym: TermSymbol): Boolean = {
    def isGetter = if (sym.owner.asClass.isCaseClass) sym.isCaseAccessor else sym.isGetter
    sym.isPublic && isGetter
  }

  ////////////////////////////////////////
  // End shapeless transplanted methods //
  ////////////////////////////////////////

  def fieldSymbolsOf(tpe: Type): List[(TermSymbol, Type)] = {
    val tSym = tpe.typeSymbol
    if(tSym.isClass && isAnonOrRefinement(tSym)) Nil
    else
      tpe.decls.sorted collect {
        case sym: TermSymbol if isCaseAccessorLike(sym) =>
          (sym, sym.typeSignatureIn(tpe).finalResultType)
      }
  }

  /**
    * A macro to materialize a more performant set of getters for [[StructDataEncoder]] over a case class.
    */
  def materialize[A <: Product : WeakTypeTag]: Expr[StructDataEncoder[A]] = {
    val A = weakTypeOf[A].dealias.widen

    def isEncodable(sym: TermSymbol, typ: Type): Boolean =
      !(typ =:= typeOf[Unit]) && !sym.isSetter && (!sym.isMethod || sym.asMethod.paramLists.flatten.isEmpty)

    val encodableFields = fieldSymbolsOf(A).collect {
      case (sym, typ) if isEncodable(sym, typ) => (sym.name.toTermName, typ)
    }

    encodableFields match {
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

            val (encodeFields, sizeFields, stringifyFields) = fields.zip(encoderVariables).map {
              case ((name, typ), encoderVariable) =>
                val encode = q"${encoderVariable.name}.encode(output, value.$name)"
                val size = q"${encoderVariable.name}.sizeOf(value.$name)"
                val stringifier = q"""${encoderVariable.name}.encodeDisplayString(value.$name).replace("\n", "\n  ")"""
                (encode, size, stringifier)
            }.unzip3

            val sizeOf = sizeFields.reduceRight {
              (nextSize, totalSize) => q"polynote.runtime.DataEncoder.combineSize($nextSize, $totalSize)"
            }

            val matchFields = fields.zip(encoderVariables).map {
              case ((name, typ), encoderVariable) =>
                cq"${name.decodedName.toString} => Some((((value: $A) => value.$name), ${encoderVariable.name}))"
            } :+ cq"_ => None"

            val displayPrefix = A.typeSymbol.name.decodedName.toString
            def displayField(name: TermName, stringifier: Tree, last: Boolean): Tree = q"""
                  sb.append("  ")
                  sb.append(${name.decodedName.toString})
                  sb.append(" = ")
                  sb.append($stringifier)
                  sb.append(${if (last) "\n" else ",\n"})
                """

            val displayFields = fields.zip(stringifyFields).reverse match {
              case Nil => Nil
              case ((lastName, _), lastString) :: rest => rest.reverse.map {
                case ((name, _), stringifier) => displayField(name, stringifier, last = false)
              } :+ displayField(lastName, lastString, last = true)
            }

            val result = q"""
              ..$encoderVariables
              new _root_.polynote.runtime.DataEncoder.StructDataEncoder[$A]($structType) {
                def encode(output: java.io.DataOutput, value: $A): Unit = { ..$encodeFields }
                def sizeOf(value: $A): _root_.scala.Int = $sizeOf
                def field(name: String): Option[($A => Any, _root_.polynote.runtime.DataEncoder[_])] = name match { case ..$matchFields }
                override def encodeDisplayString(value: $A): String = {
                  val sb = new java.lang.StringBuilder()
                  sb.append($displayPrefix)
                  sb.append("(\n")
                  ..$displayFields
                  sb.append(")")
                  sb.toString()
                }
              }
            """

            try c.Expr[StructDataEncoder[A]](c.typecheck(result)) catch {
              case err: Throwable =>
                val e = err
                c.warning(c.enclosingPosition, s"Unable to derive StructDataEncoder for ${A.toString}: ${e.getMessage}")
                c.abort(c.enclosingPosition, e.getMessage)
            }
        }
    }

  }

}
