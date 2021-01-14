package polynote.kernel.interpreter.jav.javarepl.expressions;

public final class Assignment extends Expression {
    private final String key;
    private final String value;

    public Assignment(String source, String key, String value) {
        super(source);
        this.key = key;
        this.value = value;
    }

    public String key() {
        return key;
    }

    public String value() {
        return value;
    }
}
