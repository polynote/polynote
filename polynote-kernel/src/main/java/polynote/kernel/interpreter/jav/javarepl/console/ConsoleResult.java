package polynote.kernel.interpreter.jav.javarepl.console;

import com.googlecode.totallylazy.Sequence;

import static com.googlecode.totallylazy.Sequences.empty;

public class ConsoleResult {
    private final String expression;
    private final Sequence<ConsoleLog> logs;

    public ConsoleResult(String expression, Sequence<ConsoleLog> logs) {
        this.expression = expression;
        this.logs = logs;
    }

    public String expression() {
        return expression;
    }

    public Sequence<ConsoleLog> logs() {
        return logs;
    }

    public static ConsoleResult emptyResult() {
        return new ConsoleResult("", empty(ConsoleLog.class));
    }

    @Override
    public String toString() {
        return expression + " => " + logs.toString("(", ", ", ")");
    }
}
