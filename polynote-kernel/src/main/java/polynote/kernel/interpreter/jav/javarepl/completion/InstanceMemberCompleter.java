package polynote.kernel.interpreter.jav.javarepl.completion;

import com.googlecode.totallylazy.Option;
import com.googlecode.totallylazy.Sequence;
import com.googlecode.totallylazy.Sequences;
import com.googlecode.totallylazy.reflection.Types;
import polynote.kernel.interpreter.jav.javarepl.Evaluator;
import polynote.kernel.interpreter.jav.javarepl.reflection.ClassReflection;
import polynote.kernel.interpreter.jav.javarepl.reflection.MemberReflection;

import java.lang.reflect.Type;

import static com.googlecode.totallylazy.Characters.characters;
import static com.googlecode.totallylazy.Option.none;
import static com.googlecode.totallylazy.Strings.startsWith;
import static com.googlecode.totallylazy.predicates.Predicates.not;
import static com.googlecode.totallylazy.predicates.Predicates.where;
import static polynote.kernel.interpreter.jav.javarepl.completion.Completions.*;
import static polynote.kernel.interpreter.jav.javarepl.reflection.ClassReflections.reflectionOf;
import static polynote.kernel.interpreter.jav.javarepl.reflection.MemberReflections.isPublic;
import static polynote.kernel.interpreter.jav.javarepl.reflection.MemberReflections.isStatic;

public class InstanceMemberCompleter extends Completer {

    private final Evaluator evaluator;

    public InstanceMemberCompleter(Evaluator evaluator) {
        this.evaluator = evaluator;
    }

    public CompletionResult call(String expression) throws Exception {
        final int lastSeparator = lastIndexOfSeparator(characters(" "), expression) + 1;
        final String packagePart = expression.substring(lastSeparator);
        final Boolean canComplete = packagePart.matches("[a-zA-Z0-9\\$_\\\\.\\(\\[\\]]*") && packagePart.contains(".");

        final int beginIndex = packagePart.lastIndexOf('.') + 1;
        Option<Class<?>> aClass = canComplete
                ? evaluator.typeOfExpression(packagePart.substring(0, beginIndex - 1)).map(Types::classOf)
                : none();

        if (aClass.isDefined()) {
            ClassReflection classReflection = reflectionOf(aClass.get());

            Sequence<MemberReflection<?>> join = Sequences.empty()
                    .join(classReflection.declaredFields())
                    .join(classReflection.declaredMethods())
                    .unique()
                    .unsafeCast();

            Sequence<CompletionCandidate> candidates = join
                    .filter(isPublic().and(not(isStatic())))
                    .groupBy(candidateName())
                    .map(candidate())
                    .filter(where(CompletionCandidate::value, startsWith(packagePart.substring(beginIndex))));

            return new CompletionResult(expression, lastSeparator + beginIndex, candidates);
        } else {
            return new CompletionResult(expression, 0, Sequences.empty(CompletionCandidate.class));
        }
    }
}
