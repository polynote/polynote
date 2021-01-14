package polynote.kernel.interpreter.jav.javarepl.completion;

import com.googlecode.totallylazy.Sequence;
import com.googlecode.totallylazy.functions.Function1;
import polynote.kernel.interpreter.jav.javarepl.Evaluator;
import polynote.kernel.interpreter.jav.javarepl.Result;
import polynote.kernel.interpreter.jav.javarepl.expressions.Expression;
import polynote.kernel.interpreter.jav.javarepl.expressions.Import;
import polynote.kernel.interpreter.jav.javarepl.expressions.Method;
import polynote.kernel.interpreter.jav.javarepl.expressions.Type;

import static com.googlecode.totallylazy.Strings.*;
import static com.googlecode.totallylazy.predicates.Predicates.in;
import static com.googlecode.totallylazy.predicates.Predicates.where;
import static polynote.kernel.interpreter.jav.javarepl.completion.CompletionCandidate.asCompletionCandidate;

public class ConsoleCompleter extends Completer {
    private final Evaluator evaluator;
    private final TypeResolver typeResolver;

    public ConsoleCompleter(Evaluator evaluator, TypeResolver typeResolver) {
        this.evaluator = evaluator;
        this.typeResolver = typeResolver;
    }

    public CompletionResult call(String expression) throws Exception {
        int lastSpace = expression.lastIndexOf(" ") + 1;
        Sequence<CompletionCandidate> candidates = results()
                .join(methods())
                .join(types())
                .join(imports())
                .filter(startsWith(expression.substring(lastSpace)))
                .map(asCompletionCandidate());

        return new CompletionResult(expression, lastSpace, candidates);
    }

    private Sequence<String> results() {
        return evaluator.results().map(Result::key);
    }

    private Sequence<String> methods() {
        return evaluator.expressionsOfType(Method.class).map(Method::name).unique().map(format("%s("));
    }

    private Sequence<String> types() {
        return evaluator.expressionsOfType(Type.class).map(Expression::key);
    }

    private Sequence<String> imports() {
        Sequence<String> importedPackages = evaluator
                .expressionsOfType(Import.class)
                .map(((Function1<Import, String>) Import::typePackage).then(replace(".*", "")).then(replace(" ", "")));

        return typeResolver.packages()
                .filter(where(ResolvedPackage::packageName, in(importedPackages)))
                .flatMap(ResolvedPackage::classes)
                .map(ResolvedClass::className)
                .unique();
    }
}
