package polynote.kernel.interpreter.jav.javarepl.reflection;

import com.googlecode.totallylazy.predicates.LogicalPredicate;

import static com.googlecode.totallylazy.predicates.Predicates.instanceOf;
import static java.lang.reflect.Modifier.*;

public final class MemberReflections {

    public static <T extends MemberReflection<?>> LogicalPredicate<T> modifier(final int modifier) {
        return new LogicalPredicate<T>() {
            public boolean matches(T member) {
                return (member.modifiers() & modifier) != 0;
            }
        };
    }

    public static <T extends MemberReflection<?>> LogicalPredicate<T> isAbstract() {
        return modifier(ABSTRACT);
    }

    public static <T extends MemberReflection<?>> LogicalPredicate<T> isFinal() {
        return modifier(FINAL);
    }

    public static <T extends MemberReflection<?>> LogicalPredicate<T> isInterface() {
        return modifier(INTERFACE);
    }

    public static <T extends MemberReflection<?>> LogicalPredicate<T> isNative() {
        return modifier(NATIVE);
    }

    public static <T extends MemberReflection<?>> LogicalPredicate<T> isPrivate() {
        return modifier(PRIVATE);
    }

    public static <T extends MemberReflection<?>> LogicalPredicate<T> isProtected() {
        return modifier(PROTECTED);
    }

    public static <T extends MemberReflection<?>> LogicalPredicate<T> isPublic() {
        return modifier(PUBLIC);
    }

    public static <T extends MemberReflection<?>> LogicalPredicate<T> isStatic() {
        return modifier(STATIC);
    }

    public static <T extends MemberReflection<?>> LogicalPredicate<T> isStrict() {
        return modifier(STRICT);
    }

    public static <T extends MemberReflection<?>> LogicalPredicate<T> isSynchronized() {
        return modifier(SYNCHRONIZED);
    }

    public static <T extends MemberReflection<?>> LogicalPredicate<T> isTransient() {
        return modifier(TRANSIENT);
    }

    public static <T extends MemberReflection<?>> LogicalPredicate<T> isVolatile() {
        return modifier(VOLATILE);
    }

    public static <T extends MemberReflection<?>> LogicalPredicate<T> isSynthetic() {
        return modifier(0x00001000);
    }

    public static <T extends MemberReflection<?>> LogicalPredicate<T> isType() {
        return instanceOf(ClassReflection.class);
    }

    public static <T extends MemberReflection<?>> LogicalPredicate<T> isMethod() {
        return instanceOf(MethodReflection.class);
    }

    public static <T extends MemberReflection<?>> LogicalPredicate<T> isField() {
        return instanceOf(FieldReflection.class);
    }

    public static <T extends MemberReflection<?>> LogicalPredicate<T> isConstructor() {
        return instanceOf(ConstructorReflection.class);
    }
}
