package polynote.kernel.interpreter.jav.javarepl;

import com.googlecode.totallylazy.Option;
import com.googlecode.totallylazy.functions.Function1;
import polynote.kernel.interpreter.jav.javarepl.expressions.Expression;

public class Evaluation {
    private final Expression expression;
    private final Option<Result> result;

    private Evaluation(Expression expression, Option<Result> result) {
        this.expression = expression;
        this.result = result;
    }

    public Expression expression() {
        return expression;
    }

    public Option<Result> result() {
        return result;
    }

    public static Evaluation evaluation(Expression expression, Option<Result> result) {
        return new Evaluation(expression, result);
    }


    @Override
    public String toString() {
        return expression + "=>" + result;
    }

    @Override
    public int hashCode() {
        return (expression != null ? expression.hashCode() : 0) +
                (result != null ? result.hashCode() : 0);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Evaluation &&
                (expression != null && expression.equals(((Evaluation) other).expression)) &&
                (result != null && result.equals(((Evaluation) other).result));
    }

    public static final class functions {
        public static Function1<Evaluation, Option<Result>> result() {
            return value -> value.result;
        }

        public static Function1<Evaluation, Expression> expression() {
            return value -> value.expression;
        }
    }
}
