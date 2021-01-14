package polynote.kernel.interpreter.jav.javarepl.console.commands;

import com.googlecode.totallylazy.Option;
import com.googlecode.totallylazy.Strings;
import polynote.kernel.interpreter.jav.javarepl.Evaluator;
import polynote.kernel.interpreter.jav.javarepl.completion.CommandCompleter;
import polynote.kernel.interpreter.jav.javarepl.console.ConsoleLogger;

import static com.googlecode.totallylazy.Files.asFile;
import static com.googlecode.totallylazy.Strings.startsWith;
import static java.lang.String.format;
import static polynote.kernel.interpreter.jav.javarepl.Utils.throwException;

public final class LoadSourceFile extends Command {
    private static final String COMMAND = ":load";
    private final Evaluator evaluator;
    private final ConsoleLogger logger;

    public LoadSourceFile(Evaluator evaluator, ConsoleLogger logger) {
        super(COMMAND + " <path> - loads source file ", startsWith(COMMAND), new CommandCompleter(COMMAND));
        this.evaluator = evaluator;
        this.logger = logger;
    }

    public void execute(String expression) {
        Option<String> path = parseStringCommand(expression).second();

        if (!path.isEmpty()) {
            try {
                evaluator.evaluate(Strings.lines(path.map(asFile()).get()).toString("\n"))
                        .leftOption()
                        .forEach(throwException());
                logger.success(format("Loaded source file from %s", path.get()));
            } catch (Exception e) {
                logger.error(format("Could not load source file from %s.\n  %s", path.get(), e.getLocalizedMessage()));
            }
        } else {
            logger.error(format("Path not specified"));
        }
    }


}
