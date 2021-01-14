package polynote.kernel.interpreter.jav.javarepl.completion;

import com.googlecode.totallylazy.Group;
import com.googlecode.totallylazy.Sequence;
import com.googlecode.totallylazy.comparators.Comparators;
import com.googlecode.totallylazy.functions.Function1;
import com.googlecode.totallylazy.match;
import polynote.kernel.interpreter.jav.javarepl.reflection.ClassReflection;
import polynote.kernel.interpreter.jav.javarepl.reflection.MemberReflection;
import polynote.kernel.interpreter.jav.javarepl.reflection.MethodReflection;

import java.lang.reflect.*;

import static com.googlecode.totallylazy.Sequences.sequence;
import static com.googlecode.totallylazy.numbers.Numbers.maximum;

public class Completions {

    public static Function1<MemberReflection<?>, String> candidateName() {
        return memberReflection -> new match<MemberReflection<?>, String>() {
            String value(MethodReflection expr) {
                return expr.name() + "(";
            }

            String value(ClassReflection expr) {
                return expr.member().getSimpleName();
            }

            String value(MemberReflection<?> expr) {
                return expr.name();
            }
        }.apply(memberReflection).get();
    }


    public static Function1<MemberReflection<?>, String> candidateForm() {
        return memberReflection -> new match<MemberReflection<?>, String>() {
            String value(MethodReflection expr) {
                return genericMethodSignature(expr.member());
            }

            String value(ClassReflection expr) {
                return expr.member().getSimpleName();
            }

            String value(MemberReflection<?> expr) {
                return expr.name();
            }
        }.apply(memberReflection).get();
    }

    public static Function1<Group<String, MemberReflection<?>>, CompletionCandidate> candidate() {
        return group -> new CompletionCandidate(group.key(), group.map(candidateForm()).sort(Comparators.ascending(String.class)));
    }

    private static String renderSimpleGenericType(Type type) {
        return new match<Type, String>() {
            String value(ParameterizedType t) {
                return renderSimpleGenericType(t.getRawType()) + sequence(t.getActualTypeArguments()).map(Completions::renderSimpleGenericType).toString("<", ", ", ">");
            }

            String value(TypeVariable<?> t) {
                return t.getName();
            }

            String value(GenericArrayType t) {
                return renderSimpleGenericType(t.getGenericComponentType()) + "[]";
            }

            String value(Class<?> t) {
                return t.getSimpleName();
            }

            String value(WildcardType t) {
                return t.toString();
            }
        }.apply(type).getOrThrow(new IllegalArgumentException("Generic type not matched (" + type.getClass() + ")"));
    }

    private static String genericMethodSignature(Method method) {
        return renderSimpleGenericType(method.getGenericReturnType()) + " " +

                method.getName() +

                sequence(method.getGenericParameterTypes())
                        .map(Completions::renderSimpleGenericType)
                        .toString("(", ", ", ")") +

                sequence(method.getGenericExceptionTypes())
                        .map(Completions::renderSimpleGenericType)
                        .toString(method.getGenericExceptionTypes().length > 0 ? " throws " : "", ", ", "");
    }

    public static int lastIndexOfSeparator(Sequence<Character> characters, String expression) {
        return characters
                .map(expression::lastIndexOf)
                .reduce(maximum())
                .intValue();
    }

}
