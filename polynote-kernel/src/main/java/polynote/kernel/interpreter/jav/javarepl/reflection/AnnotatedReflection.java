package polynote.kernel.interpreter.jav.javarepl.reflection;

import com.googlecode.totallylazy.Sequence;

import java.lang.annotation.Annotation;

public interface AnnotatedReflection {

    Sequence<Annotation> annotations();

    Sequence<Annotation> declaredAnnotations();
}
