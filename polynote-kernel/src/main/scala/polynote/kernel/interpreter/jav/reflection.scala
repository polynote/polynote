package polynote.kernel.interpreter.jav

import java.lang.reflect.{Constructor, Field, Method}

package object reflection {
  sealed trait MemberReflection[T] {
    def member: T

    def modifiers: Int

    def name: String

    def isSynthetic: Boolean

    override def toString: String = getClass.getSimpleName + "(" + member + ")"
  }

  case class FieldReflection(member: Field) extends MemberReflection[Field] {
    override def modifiers: Int = member.getModifiers

    override def name: String = member.getName

    override def isSynthetic: Boolean = member.isSynthetic
  }

  case class MethodReflection(member: Method) extends MemberReflection[Method] {
    override def modifiers: Int = member.getModifiers

    override def name: String = member.getName

    override def isSynthetic: Boolean = member.isSynthetic
  }

  case class ConstructorReflection(member: Constructor[_]) extends MemberReflection[Constructor[_]] {
    override def modifiers: Int = member.getModifiers

    override def name: String = member.getName

    override def isSynthetic: Boolean = member.isSynthetic
  }

  case class ClassReflection(member: Class[_]) extends MemberReflection[Class[_]] {
    override def modifiers: Int = member.getModifiers

    override def name: String = member.getName

    def declaredFields: Seq[FieldReflection] = member.getDeclaredFields.map(FieldReflection)

    def declaredMethods: Seq[MethodReflection] = member.getDeclaredMethods.map(MethodReflection)

    def declaredClasses: Seq[ClassReflection] = member.getDeclaredClasses.map(ClassReflection)

    def declaredConstructors: Seq[ConstructorReflection] = member.getDeclaredConstructors.map(ConstructorReflection)

    def declaredMembers: Seq[MemberReflection[_]] =
      (declaredClasses ++
        declaredConstructors++
        declaredFields ++
        declaredMethods).distinct

    override def isSynthetic: Boolean = member.isSynthetic
  }


}
