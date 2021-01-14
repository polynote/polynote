package polynote.kernel.interpreter.jav.javarepl.rendering;

import com.googlecode.totallylazy.Strings;
import com.googlecode.totallylazy.annotations.multimethod;
import com.googlecode.totallylazy.multi;
import polynote.kernel.interpreter.jav.javarepl.EvaluationContext;
import polynote.kernel.interpreter.jav.javarepl.EvaluationTemplate;
import polynote.kernel.interpreter.jav.javarepl.expressions.*;

import static com.googlecode.totallylazy.Sequences.sequence;
import static com.googlecode.totallylazy.Strings.blank;
import static com.googlecode.totallylazy.Strings.replaceAll;
import static com.googlecode.totallylazy.predicates.Predicates.not;
import static java.lang.String.format;
import static polynote.kernel.interpreter.jav.javarepl.expressions.Expression.functions.source;
import static polynote.kernel.interpreter.jav.javarepl.expressions.Patterns.methodPattern;
import static polynote.kernel.interpreter.jav.javarepl.rendering.ExpressionTokenRenderer.renderExpressionToken;
import static polynote.kernel.interpreter.jav.javarepl.rendering.MethodNameRenderer.renderMethodName;
import static polynote.kernel.interpreter.jav.javarepl.rendering.TypeRenderer.renderType;

public class EvaluationClassRenderer {
    @multimethod
    public static String renderExpressionClass(EvaluationContext context, String className, Expression expression) {
        return new multi() {
        }.<String>methodOption(context, className, expression)
                .getOrThrow(new IllegalArgumentException(expression + " not mapped"));
    }

    @multimethod
    private static String renderExpressionClass(EvaluationContext context, String className, Value expression) {
        return render(context, className, expression);
    }

    @multimethod
    private static String renderExpressionClass(EvaluationContext context, String className, Statement expression) {
        return render(context, className, expression);
    }

    @multimethod
    private static String renderExpressionClass(EvaluationContext context, String className, Assignment expression) {
        return render(context, className, expression);
    }

    @multimethod
    private static String renderExpressionClass(EvaluationContext context, String className, AssignmentWithType expression) {
        return render(context, className, expression);
    }

    @multimethod
    private static String renderExpressionClass(EvaluationContext context, String className, Import expression) {
        return renderFragments(
                renderPreviousImports(context),
                renderExpressionToken(expression),
                renderClassName(className),
                renderClassConstructor(className),
                renderPreviousEvaluations(context),
                renderMethodName(expression),
                renderEndOfMethod(),
                renderEndOfFile()
        );
    }

    @multimethod
    private static String renderExpressionClass(EvaluationContext context, String className, Method expression) {
        return renderFragments(
                renderPreviousImports(context),
                renderClassName(className),
                renderClassConstructor(className),
                renderPreviousEvaluations(context),
                renderPreviousMethods(context),
                renderExpressionToken(expression),
                renderMethodName(expression),
                renderEndOfMethod(),
                renderEndOfFile()
        );
    }

    @multimethod
    private static String renderExpressionClass(EvaluationContext context, String className, Type expression) {
        return renderFragments(
                expression.typePackage().isEmpty() ? renderPreviousImports(context) : "",
                expression.source()
        );
    }

    public static String renderMethodSignatureDetection(EvaluationContext context, String className, String expression) {
        return renderFragments(
                renderPreviousImports(context),
                renderInterfaceName(className),
                renderInterfaceMethod(expression),
                renderEndOfFile()
        );
    }

    private static String renderInterfaceName(String className) {
        return format("public interface %s {", className);
    }

    private static String renderInterfaceMethod(String expression) {
        String signature = methodPattern.match(expression).group(1);
        String type = methodPattern.match(expression).group(2);
        return format("  %s;", signature.substring(signature.indexOf(type)));
    }

    private static String render(EvaluationContext context, String className, Expression expression) {
        return renderFragments(
                renderPreviousImports(context),
                renderClassName(className),
                renderClassConstructor(className),
                renderPreviousEvaluations(context),
                renderPreviousMethods(context),
                renderMethodName(expression),
                renderExpressionToken(expression),
                renderEndOfMethod(),
                renderEndOfFile()
        );
    }


    private static String renderPreviousEvaluations(EvaluationContext context) {
        return context
                .results()
                .map(result -> format("  public %s %s = valueOf(\"%s\");", renderType(result.type()), result.key(), result.key()))
                .toString("\n");
    }

    private static String renderPreviousMethods(EvaluationContext context) {
        return context.expressionsOfType(Method.class)
                .map(source().then(replaceAll("\n", "\n  ")).then(Strings.format("  %s")))
                .toString("\n\n");
    }

    private static String renderPreviousImports(EvaluationContext context) {
        return context.expressionsOfType(Import.class)
                .map(source().then(Strings.format("%s;")))
                .toString("\n");
    }

    private static String renderClassName(String className) {
        return format("public final class %s extends %s {", className, EvaluationTemplate.class.getCanonicalName());
    }

    private static String renderClassConstructor(String className) {
        return format("  public %s(%s context) { super(context); }", className, EvaluationContext.class.getCanonicalName());
    }

    private static String renderEndOfMethod() {
        return format("  }");
    }

    private static String renderEndOfFile() {
        return format("}");
    }

    private static String renderFragments(String... elements) {
        return sequence(elements).filter(not(blank)).toString("\n");
    }

}
