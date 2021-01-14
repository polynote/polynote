package polynote.kernel.interpreter.jav.javarepl.console.commands;

import polynote.kernel.interpreter.jav.javarepl.console.ConsoleLogger;

import static com.googlecode.totallylazy.Strings.startsWith;

public final class NotAValidCommand extends Command {
    private final ConsoleLogger logger;

    public NotAValidCommand(ConsoleLogger logger) {
        super(null, startsWith(":"), null);
        this.logger = logger;
    }

    public void execute(String expression) {
        logger.error(expression + " is not a valid command");
    }
}
