package polynote.kernel.interpreter.jav.javarepl.console.commands;

import com.googlecode.totallylazy.Strings;
import polynote.kernel.interpreter.jav.javarepl.Evaluator;
import polynote.kernel.interpreter.jav.javarepl.completion.CommandCompleter;
import polynote.kernel.interpreter.jav.javarepl.console.ConsoleHistory;
import polynote.kernel.interpreter.jav.javarepl.console.ConsoleLogger;

import static com.googlecode.totallylazy.Strings.startsWith;
import static java.lang.String.format;
import static polynote.kernel.interpreter.jav.javarepl.Utils.resolveURL;

public final class EvaluateFile extends Command {
    private static final String COMMAND = ":eval";
    private final ConsoleLogger logger;
    private final Evaluator evaluator;
    private final ConsoleHistory history;

    public EvaluateFile(ConsoleLogger logger, Evaluator evaluator, ConsoleHistory history) {
        super(COMMAND + " <path> - evaluates all expressions from file (expression per line)", startsWith(COMMAND), new CommandCompleter(COMMAND));
        this.logger = logger;
        this.evaluator = evaluator;
        this.history = history;
    }

    public void execute(String expression) {
        String path = parseStringCommand(expression).second().getOrNull();
        try {
            for (String line : Strings.lines(resolveURL(path).openStream())) {
                history.add(line);
                EvaluateExpression.evaluate(evaluator, logger, line);
            }

            logger.success(format("Finished evaluating %s", path));
        } catch (Exception e) {
            logger.error(format("Could evaluate %s. %s", path, e.getLocalizedMessage()));
        }
    }
}
