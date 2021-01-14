package polynote.kernel.interpreter.jav.javarepl.console.commands;

import polynote.kernel.interpreter.jav.javarepl.Evaluator;
import polynote.kernel.interpreter.jav.javarepl.completion.CommandCompleter;
import polynote.kernel.interpreter.jav.javarepl.console.ConsoleLogger;

import static com.googlecode.totallylazy.predicates.Predicates.equalTo;

public final class ResetAllEvaluations extends Command {
    private static final String COMMAND = ":reset";
    private final Evaluator evaluator;
    private final ConsoleLogger logger;

    public ResetAllEvaluations(Evaluator evaluator, ConsoleLogger logger) {
        super(COMMAND + " - resets environment to initial state", equalTo(COMMAND), new CommandCompleter(COMMAND));
        this.evaluator = evaluator;
        this.logger = logger;
    }

    public void execute(String expression) {
        evaluator.reset();
        logger.success("All variables has been cleared");
    }
}
