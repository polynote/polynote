package polynote.kernel.interpreter.jav.javarepl.expressions;

import com.googlecode.totallylazy.Sequence;
import polynote.kernel.interpreter.jav.javarepl.rendering.TypeRenderer;

import static polynote.kernel.interpreter.jav.javarepl.Utils.canonicalName;

public final class Method extends Expression {
    private final java.lang.reflect.Type type;
    private final String name;
    private final Sequence<Class<?>> arguments;

    public Method(String source, java.lang.reflect.Type type, String name, Sequence<Class<?>> arguments) {
        super(source);

        this.type = type;
        this.name = name;
        this.arguments = arguments;
    }

    public String name() {
        return name;
    }

    public java.lang.reflect.Type type() {
        return type;
    }

    public Sequence<Class<?>> arguments() {
        return arguments;
    }

    public String signature() {
        return TypeRenderer.renderType(type) + " " + name + arguments.map(canonicalName()).toString("(", ", ", ")");
    }

    public String key() {
        return name + arguments.map(canonicalName()).toString(", ");
    }

}
