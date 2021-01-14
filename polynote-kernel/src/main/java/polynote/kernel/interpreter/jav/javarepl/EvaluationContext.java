package polynote.kernel.interpreter.jav.javarepl;

import com.googlecode.totallylazy.Option;
import com.googlecode.totallylazy.Sequence;
import polynote.kernel.interpreter.jav.javarepl.expressions.Expression;
import polynote.kernel.interpreter.jav.javarepl.expressions.Import;

import java.io.File;

import static com.googlecode.totallylazy.Option.none;
import static com.googlecode.totallylazy.Option.option;
import static com.googlecode.totallylazy.Sequences.empty;
import static com.googlecode.totallylazy.Sequences.join;
import static com.googlecode.totallylazy.Sequences.sequence;
import static com.googlecode.totallylazy.predicates.Predicates.equalTo;
import static com.googlecode.totallylazy.predicates.Predicates.instanceOf;
import static com.googlecode.totallylazy.predicates.Predicates.not;
import static com.googlecode.totallylazy.predicates.Predicates.where;
import static polynote.kernel.interpreter.jav.javarepl.Utils.javaVersionAtLeast;
import static polynote.kernel.interpreter.jav.javarepl.Utils.randomOutputDirectory;

public class EvaluationContext {
    private final File outputDirectory;
    private final Sequence<Expression> expressions;
    private final Sequence<Result> results;
    private final Option<String> lastSource;

    private EvaluationContext(File outputDirectory, Sequence<Expression> expressions, Sequence<Result> results, Option<String> lastSource) {
        this.outputDirectory = outputDirectory;
        this.expressions = expressions;
        this.results = results;
        this.lastSource = lastSource;
    }

    public static EvaluationContext evaluationContext() {
        return evaluationContext(null);
    }

    public static EvaluationContext evaluationContext(final File outputDirectory) {
        return new EvaluationContext(
                outputDirectory != null ? outputDirectory : randomOutputDirectory(),
                defaultExpressions(), empty(Result.class), none(String.class));
    }

    private static Sequence<Expression> defaultJavaImports() {
        return sequence(
                new Import("import java.lang.*", "java.lang.*"),
                new Import("import java.util.*", "java.util.*"),
                new Import("import java.math.*", "java.math.*"),
                new Import("import static java.lang.Math.*", "java.lang.Math.*")
        ).safeCast(Expression.class);
    }

    private static Sequence<Expression> defaultJava8Imports() {
        return javaVersionAtLeast("1.8.0")
                ? sequence(new Import("import java.util.function.*", "java.util.function.*")).safeCast(Expression.class)
                : empty(Expression.class);
    }

    public static Sequence<Expression> defaultExpressions() {
        return join(defaultJavaImports(), defaultJava8Imports());
    }

    public File outputDirectory() {
        return outputDirectory;
    }

    public Option<String> lastSource() {
        return lastSource;
    }

    public Sequence<Result> results() {
        return results
                .reverse()
                .unique(Result::key)
                .reverse();
    }

    public Sequence<Expression> expressions() {
        return expressions;
    }

    public <T extends Expression> Sequence<T> expressionsOfType(Class<T> type) {
        return expressions
                .filter(instanceOf(type))
                .safeCast(type);
    }

    public Option<Result> result(final String key) {
        return results().filter(where(Result::key, equalTo(key))).headOption();
    }

    public String nextResultKey() {
        return "res" + results().size();
    }

    public EvaluationContext lastSource(String lastSource) {
        return new EvaluationContext(outputDirectory, expressions, results, option(lastSource));
    }

    public EvaluationContext addResult(Result result) {
        return new EvaluationContext(outputDirectory, expressions, results.append(result), lastSource);
    }

    public EvaluationContext addResults(Sequence<Result> result) {
        return new EvaluationContext(outputDirectory, expressions, results.join(result), lastSource);
    }

    public EvaluationContext addExpression(Expression expression) {
        return new EvaluationContext(outputDirectory, expressions.append(expression), results, lastSource);
    }

    public EvaluationContext removeExpressionWithKey(String key) {
        return new EvaluationContext(outputDirectory, expressions.filter(where(Expression::key, not(key))), results, lastSource);
    }
}
