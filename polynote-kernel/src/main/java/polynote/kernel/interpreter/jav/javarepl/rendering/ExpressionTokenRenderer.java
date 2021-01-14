package polynote.kernel.interpreter.jav.javarepl.rendering;

import com.googlecode.totallylazy.annotations.multimethod;
import com.googlecode.totallylazy.multi;
import polynote.kernel.interpreter.jav.javarepl.expressions.*;

import static java.lang.String.format;

public class ExpressionTokenRenderer {
    public static final String EXPRESSION_TOKEN = "$JAVAREPL_EXPRESSION_TOKEN$";
    public static final String EXPRESSION_VALUE = "$JAVAREPL_EXPRESSION_VALUE$";

    @multimethod
    public static String renderExpressionToken(Expression expression) {
        return new multi() {
        }.<String>methodOption(expression)
                .getOrThrow(new IllegalArgumentException(expression + " not mapped"));
    }

    @multimethod
    private static String renderExpressionToken(Statement expression) {
        return "    " + EXPRESSION_TOKEN + ";";
    }

    @multimethod
    private static String renderExpressionToken(Assignment expression) {
        return expressionWithValue(expression.value());
    }

    @multimethod
    private static String renderExpressionToken(AssignmentWithType expression) {
        return expressionWithValue(expression.value(), expression.type());
    }

    @multimethod
    private static String renderExpressionToken(Value expression) {
        return expressionWithValue(expression.source());
    }

    @multimethod
    private static String renderExpressionToken(Method expression) {
        return "  " + EXPRESSION_TOKEN + "\n";
    }

    @multimethod
    private static String renderExpressionToken(Import expression) {
        return EXPRESSION_TOKEN + ";";
    }

    private static String expressionWithValue(String value) {
        return expressionWithValue(value, Object.class);
    }

    private static String expressionWithValue(String value, java.lang.reflect.Type returnType) {
        return expressionWithValue(value, TypeRenderer.renderType(returnType));
    }

    private static String expressionWithValue(String value, String returnType) {
        return format("    %s %s =\n\n    %s;\n\n    return %s;", returnType, EXPRESSION_VALUE, EXPRESSION_TOKEN, EXPRESSION_VALUE);
    }
}
