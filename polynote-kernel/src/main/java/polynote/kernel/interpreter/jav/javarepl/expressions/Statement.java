package polynote.kernel.interpreter.jav.javarepl.expressions;

import static java.util.UUID.randomUUID;

public final class Statement extends Expression {
    public Statement(String source) {
        super(source);
    }

    public String key() {
        return randomUUID().toString();
    }
}
