package polynote.kernel.interpreter.jav.javarepl.rendering;

import com.googlecode.totallylazy.annotations.multimethod;
import com.googlecode.totallylazy.multi;
import polynote.kernel.interpreter.jav.javarepl.expressions.*;

public class ExpressionSourceRenderer {
    @multimethod
    public static String renderExpressionSource(Expression expression) {
        return new multi() {
        }.<String>methodOption(expression)
                .getOrThrow(new IllegalArgumentException(expression + " not mapped"));
    }

    @multimethod
    private static String renderExpressionSource(Statement expression) {
        return expression.source();
    }

    @multimethod
    private static String renderExpressionSource(Assignment expression) {
        return expression.value();
    }

    @multimethod
    private static String renderExpressionSource(AssignmentWithType expression) {
        return expression.value();
    }

    @multimethod
    private static String renderExpressionSource(Value expression) {
        return expression.source();
    }

    @multimethod
    private static String renderExpressionSource(Method expression) {
        return expression.source().replaceAll("\n", "\n  ");
    }

    @multimethod
    private static String renderExpressionSource(Import expression) {
        return expression.source();
    }

    @multimethod
    private static String renderExpressionSource(Type expression) {
        return expression.source();
    }
}
