package polynote.kernel.interpreter.jav.javarepl.reflection;

import com.googlecode.totallylazy.Sequence;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;

import static com.googlecode.totallylazy.Sequences.sequence;

public final class FieldReflection extends MemberReflection<Field> {

    public FieldReflection(Field field) {
        super(field);
    }

    public Integer modifiers() {
        return member().getModifiers();
    }

    public String name() {
        return member().getName();
    }

    public Sequence<Annotation> annotations() {
        return sequence(member().getAnnotations());
    }

    public Sequence<Annotation> declaredAnnotations() {
        return sequence(member().getDeclaredAnnotations());
    }
}
