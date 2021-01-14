package polynote.kernel.interpreter.jav.javarepl.completion;

import com.googlecode.totallylazy.Group;
import com.googlecode.totallylazy.Option;
import com.googlecode.totallylazy.Sequence;
import com.googlecode.totallylazy.functions.Function1;

import static com.googlecode.totallylazy.Groups.groupKey;
import static com.googlecode.totallylazy.Sequences.empty;

public class AggregateCompleter extends Completer {
    private final Sequence<Completer> completers;

    public AggregateCompleter(Sequence<Completer> completers) {
        this.completers = completers;
    }

    public CompletionResult call(String expression) throws Exception {
        Option<Group<Integer, CompletionResult>> group = completeGroup(expression);

        if (group.isEmpty())
            return new CompletionResult(expression, 0, empty(CompletionCandidate.class));

        return new CompletionResult(expression, group.get().key(), group.get().flatMap(CompletionResult::candidates));
    }

    private Option<Group<Integer, CompletionResult>> completeGroup(String expression) {
        return completers.map(complete(expression))
                .unique()
                .groupBy(CompletionResult::position)
                .sortBy(groupKey(Integer.class))
                .reverse()
                .headOption();
    }

    private Function1<Completer, CompletionResult> complete(final String expression) {
        return completer -> completer.apply(expression);
    }
}
