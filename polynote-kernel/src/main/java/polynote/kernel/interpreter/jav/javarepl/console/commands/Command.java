package polynote.kernel.interpreter.jav.javarepl.console.commands;

import com.googlecode.totallylazy.Option;
import com.googlecode.totallylazy.Pair;
import com.googlecode.totallylazy.Sequence;
import com.googlecode.totallylazy.predicates.Predicate;
import polynote.kernel.interpreter.jav.javarepl.completion.Completer;

import static com.googlecode.totallylazy.Option.none;
import static com.googlecode.totallylazy.Option.some;
import static com.googlecode.totallylazy.Sequences.sequence;
import static java.lang.Integer.parseInt;

public abstract class Command {
    public static final String COMMAND_SEPARATOR = " ";

    private final String description;
    private final Predicate<String> predicate;
    private final Completer completer;

    protected Command(String description, Predicate<String> predicate, Completer completer) {
        this.description = description;
        this.predicate = predicate;
        this.completer = completer;
    }

    public abstract void execute(String expression);

    public String description() {
        return description;
    }

    public Predicate<String> predicate() {
        return predicate;
    }

    public Completer completer() {
        return completer;
    }

    @Override
    public final String toString() {
        return description();
    }

    public static final Pair<String, Option<String>> parseStringCommand(String input) {
        final Sequence<String> splitInput = sequence(input.split(COMMAND_SEPARATOR));
        String command = splitInput.first();
        String value = splitInput.tail().toString(COMMAND_SEPARATOR);
        return Pair.pair(command, value.isEmpty() ? Option.none(String.class) : some(value));
    }

    public static final Pair<String, Option<Integer>> parseNumericCommand(String input) {
        final Sequence<String> splitInput = sequence(input.split(COMMAND_SEPARATOR));

        try {
            return Pair.pair(splitInput.first(), some(parseInt(splitInput.tail().toString(COMMAND_SEPARATOR))));
        } catch (Exception e) {
            return Pair.pair(splitInput.first(), none(Integer.class));
        }
    }

}