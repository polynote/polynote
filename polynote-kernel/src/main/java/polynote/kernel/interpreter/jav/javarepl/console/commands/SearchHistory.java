package polynote.kernel.interpreter.jav.javarepl.console.commands;

import polynote.kernel.interpreter.jav.javarepl.completion.CommandCompleter;
import polynote.kernel.interpreter.jav.javarepl.console.ConsoleHistory;
import polynote.kernel.interpreter.jav.javarepl.console.ConsoleLogger;

import static com.googlecode.totallylazy.Strings.contains;
import static com.googlecode.totallylazy.Strings.startsWith;
import static polynote.kernel.interpreter.jav.javarepl.Utils.listValues;
import static polynote.kernel.interpreter.jav.javarepl.console.commands.ShowHistory.numberedHistory;

public final class SearchHistory extends Command {
    private static final String COMMAND = ":h?";
    private final ConsoleLogger logger;
    private final ConsoleHistory history;

    public SearchHistory(ConsoleLogger logger, ConsoleHistory history) {
        super(COMMAND + " <term> - searches the history", startsWith(COMMAND), new CommandCompleter(COMMAND));
        this.logger = logger;
        this.history = history;
    }

    public void execute(String expression) {
        String searchTerm = parseStringCommand(expression).second().getOrElse("");
        logger.success(listValues("History search for '" + searchTerm + "'", numberedHistory(history).filter(contains(searchTerm))));
    }
}
