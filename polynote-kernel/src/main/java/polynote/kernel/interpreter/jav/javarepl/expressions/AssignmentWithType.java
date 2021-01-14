package polynote.kernel.interpreter.jav.javarepl.expressions;

public final class AssignmentWithType extends Expression {
    private final java.lang.reflect.Type type;
    private final String key;
    private final String value;

    public AssignmentWithType(String source, java.lang.reflect.Type type, String key, String value) {
        super(source);

        this.type = type;
        this.key = key;
        this.value = value;
    }

    public String key() {
        return key;
    }

    public java.lang.reflect.Type type() {
        return type;
    }

    public String value() {
        return value;
    }
}
