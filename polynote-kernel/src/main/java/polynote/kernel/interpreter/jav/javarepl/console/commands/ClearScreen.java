package polynote.kernel.interpreter.jav.javarepl.console.commands;

import polynote.kernel.interpreter.jav.javarepl.completion.CommandCompleter;
import polynote.kernel.interpreter.jav.javarepl.console.ConsoleLogger;

import static com.googlecode.totallylazy.Strings.startsWith;

public final class ClearScreen extends Command {
    public static final String CLEAR_SCREEN_CMD = "CLEAR_SCREEN";
    private static final String COMMAND = ":cls";
    private final ConsoleLogger logger;

    public ClearScreen(ConsoleLogger logger) {
        super(COMMAND + " - clears screen", startsWith(COMMAND), new CommandCompleter(COMMAND));
        this.logger = logger;
    }

    public void execute(String expression) {
        this.logger.reset();
        this.logger.control(CLEAR_SCREEN_CMD);
    }
}
