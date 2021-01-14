package polynote.kernel.interpreter.jav.javarepl.reflection;

import com.googlecode.totallylazy.Option;
import com.googlecode.totallylazy.Sequence;

import java.lang.annotation.Annotation;

import static com.googlecode.totallylazy.Option.option;
import static com.googlecode.totallylazy.Sequences.empty;
import static com.googlecode.totallylazy.Sequences.sequence;

public final class ClassReflection extends MemberReflection<Class<?>> {
    public ClassReflection(Class<?> aClass) {
        super(aClass);
    }

    public Integer modifiers() {
        return member().getModifiers();
    }

    public String name() {
        return member().getName();
    }

    public Option<MethodReflection> enclosingMethod() {
        return option(member().getEnclosingMethod()).map(MethodReflection::new);
    }

    public Sequence<FieldReflection> declaredFields() {
        return sequence(member().getDeclaredFields()).map(FieldReflection::new);
    }

    public Sequence<FieldReflection> fields() {
        return sequence(member().getFields()).map(FieldReflection::new);
    }

    public Sequence<MethodReflection> declaredMethods() {
        return sequence(member().getDeclaredMethods()).map(MethodReflection::new);
    }

    public Sequence<MethodReflection> methods() {
        return sequence(member().getMethods()).map(MethodReflection::new);
    }

    public Sequence<ConstructorReflection> declaredConstructors() {
        return sequence(member().getDeclaredConstructors()).map(ConstructorReflection::new);
    }

    public Sequence<ConstructorReflection> constructors() {
        return sequence(member().getConstructors()).map(ConstructorReflection::new);
    }

    public Sequence<Annotation> declaredAnnotations() {
        return sequence(member().getDeclaredAnnotations());
    }

    public Sequence<Annotation> annotations() {
        return sequence(member().getAnnotations());
    }

    private Sequence<ClassReflection> interfaces() {
        return sequence(member().getInterfaces()).map(ClassReflection::new);
    }

    private Sequence<ClassReflection> declaredClasses() {
        return sequence(member().getDeclaredClasses()).map(ClassReflection::new);
    }

    private Sequence<ClassReflection> classes() {
        return sequence(member().getClasses()).map(ClassReflection::new);
    }

    public Sequence<MemberReflection<?>> declaredMembers() {
        return empty()
                .join(declaredClasses())
                .join(declaredConstructors())
                .join(declaredFields())
                .join(declaredMethods())
                .unique()
                .unsafeCast();
    }

    public Sequence<MemberReflection<?>> members() {
        return empty()
                .join(classes())
                .join(constructors())
                .join(fields())
                .join(methods())
                .unique()
                .unsafeCast();
    }
}
