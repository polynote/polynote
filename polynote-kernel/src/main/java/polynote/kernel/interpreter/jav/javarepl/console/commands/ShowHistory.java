package polynote.kernel.interpreter.jav.javarepl.console.commands;

import com.googlecode.totallylazy.Sequence;
import com.googlecode.totallylazy.Sequences;
import com.googlecode.totallylazy.numbers.Numbers;
import polynote.kernel.interpreter.jav.javarepl.completion.CommandCompleter;
import polynote.kernel.interpreter.jav.javarepl.console.ConsoleHistory;
import polynote.kernel.interpreter.jav.javarepl.console.ConsoleLogger;

import static com.googlecode.totallylazy.Pair.functions.values;
import static com.googlecode.totallylazy.Strings.replace;
import static com.googlecode.totallylazy.Strings.startsWith;
import static polynote.kernel.interpreter.jav.javarepl.Utils.listValues;

public final class ShowHistory extends Command {
    private static final String COMMAND = ":hist";
    private final ConsoleLogger logger;
    private final ConsoleHistory history;

    public ShowHistory(ConsoleLogger logger, ConsoleHistory history) {
        super(COMMAND + " <num> - shows the history (optional 'num' is number of evaluations to show)",
                startsWith(COMMAND), new CommandCompleter(COMMAND));
        this.logger = logger;
        this.history = history;
    }

    public void execute(String expression) {
        Integer limit = parseNumericCommand(expression).second().getOrElse(history.items().size());
        Sequence<String> numberedHistory = numberedHistory(history).reverse().take(limit).reverse();

        if (!numberedHistory.isEmpty()) {
            logger.success(listValues("History", numberedHistory));
        } else {
            logger.success("No history.");
        }
    }

    public static Sequence<String> numberedHistory(ConsoleHistory consoleHistory) {
        return Numbers.range(1)
                .zip(consoleHistory.items().map(replace("\n", "\n   ")))
                .map(values().then(Sequences.toString("  ")));
    }
}
