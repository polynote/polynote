package polynote.kernel.interpreter.jav.javarepl.completion;

import com.googlecode.totallylazy.Sequence;

import static com.googlecode.totallylazy.Sequences.*;
import static com.googlecode.totallylazy.Strings.startsWith;
import static polynote.kernel.interpreter.jav.javarepl.completion.CompletionCandidate.asCompletionCandidate;

public class CommandCompleter extends Completer {
    private final String command;
    private final Sequence<String> candidates;

    public CommandCompleter(String command) {
        this(command, empty(String.class));
    }

    public CommandCompleter(String command, Sequence<String> candidates) {
        this.command = command;
        this.candidates = candidates;
    }

    public CompletionResult call(String expression) throws Exception {
        Sequence<String> parts = sequence(expression.split(" "));

        if (parts.isEmpty()) {
            return new CompletionResult(expression, 0, empty(CompletionCandidate.class));
        }

        if (command.equals(parts.head())) {
            String nextCommandPart = parts.tail().headOption().getOrElse("");
            return new CompletionResult(expression, candidates.isEmpty() ? 0 : command.length() + 1, candidates.filter(startsWith(nextCommandPart)).map(asCompletionCandidate()));
        }

        if (command.startsWith(parts.head())) {
            return new CompletionResult(expression, 0, one(asCompletionCandidate().apply(command)));
        }

        return new CompletionResult(expression, 0, empty(CompletionCandidate.class));
    }
}
