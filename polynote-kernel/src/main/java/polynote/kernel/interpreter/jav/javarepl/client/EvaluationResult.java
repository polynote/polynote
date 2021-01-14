package polynote.kernel.interpreter.jav.javarepl.client;

import com.googlecode.totallylazy.Sequence;

import java.util.List;

public final class EvaluationResult {
    private final String expression;
    private final Sequence<EvaluationLog> logs;

    EvaluationResult(String expression, Sequence<EvaluationLog> logs) {
        this.expression = expression;
        this.logs = logs;
    }

    public String expression() {
        return expression;
    }

    public List<EvaluationLog> logs() {
        return logs.toList();
    }
}
