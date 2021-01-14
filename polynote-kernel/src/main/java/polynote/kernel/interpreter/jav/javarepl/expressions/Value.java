package polynote.kernel.interpreter.jav.javarepl.expressions;

import static java.util.UUID.randomUUID;

public final class Value extends Expression {
    public Value(String source) {
        super(source);
    }

    public String key() {
        return randomUUID().toString();
    }
}
