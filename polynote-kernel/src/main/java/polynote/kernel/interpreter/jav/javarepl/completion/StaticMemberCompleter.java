package polynote.kernel.interpreter.jav.javarepl.completion;

import com.googlecode.totallylazy.Option;
import com.googlecode.totallylazy.Pair;
import com.googlecode.totallylazy.Sequence;
import com.googlecode.totallylazy.Sequences;
import com.googlecode.totallylazy.functions.Function1;
import polynote.kernel.interpreter.jav.javarepl.Evaluator;

import static com.googlecode.totallylazy.Characters.characters;
import static com.googlecode.totallylazy.Option.none;
import static com.googlecode.totallylazy.Option.some;
import static com.googlecode.totallylazy.Pair.pair;
import static com.googlecode.totallylazy.Strings.startsWith;
import static com.googlecode.totallylazy.predicates.Predicates.not;
import static com.googlecode.totallylazy.predicates.Predicates.where;
import static polynote.kernel.interpreter.jav.javarepl.completion.Completions.*;
import static polynote.kernel.interpreter.jav.javarepl.reflection.ClassReflections.reflectionOf;
import static polynote.kernel.interpreter.jav.javarepl.reflection.MemberReflections.*;

public class StaticMemberCompleter extends Completer {

    private final Evaluator evaluator;

    public StaticMemberCompleter(Evaluator evaluator) {
        this.evaluator = evaluator;
    }

    public CompletionResult call(String expression) throws Exception {
        final int lastSeparator = lastIndexOfSeparator(characters(" "), expression) + 1;
        final String packagePart = expression.substring(lastSeparator);

        Option<Pair<Class<?>, String>> completion = completionFor(packagePart);

        if (!completion.isEmpty()) {
            Function1<CompletionCandidate, String> value = CompletionCandidate::value;
            Sequence<CompletionCandidate> candidates = reflectionOf(completion.get().first())
                    .declaredMembers()
                    .filter(isStatic().and(isPublic()).and(not(isSynthetic())))
                    .groupBy(candidateName())
                    .map(candidate())
                    .filter(where(value, startsWith(completion.get().second())));

            final int beginIndex = packagePart.lastIndexOf('.') + 1;
            return new CompletionResult(expression, lastSeparator + beginIndex, candidates);
        } else {
            return new CompletionResult(expression, 0, Sequences.empty(CompletionCandidate.class));
        }
    }


    private Option<Pair<Class<?>, String>> completionFor(String expression) {
        Option<Pair<String, Sequence<String>>> parsedClass = parseExpression(pair(expression, Sequences.empty(String.class)));

        if (!parsedClass.isEmpty() && !parsedClass.get().second().isEmpty()) {
            return some(Pair.<Class<?>, String>pair(
                    evaluator.classFrom(parsedClass.get().first()).get(),
                    parsedClass.get().second().toString(".").trim()));
        } else {
            return none();
        }
    }


    private Option<Pair<String, Sequence<String>>> parseExpression(Pair<String, Sequence<String>> expression) {
        Option<Class<?>> expressionClass = evaluator.classFrom(expression.first());

        if (!expressionClass.isEmpty()) {
            return some(expression);
        }

        if (expression.first().contains(".")) {
            final String packagePart = expression.first().substring(0, expression.first().lastIndexOf("."));
            final String classPart = expression.first().substring(expression.first().lastIndexOf(".") + 1);

            return parseExpression(pair(packagePart, expression.second().cons(classPart)));
        }

        return Option.none();

    }
}
