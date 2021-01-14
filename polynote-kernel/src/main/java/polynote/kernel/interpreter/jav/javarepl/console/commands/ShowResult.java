package polynote.kernel.interpreter.jav.javarepl.console.commands;

import com.googlecode.totallylazy.predicates.Predicate;
import polynote.kernel.interpreter.jav.javarepl.Evaluator;
import polynote.kernel.interpreter.jav.javarepl.console.ConsoleLogger;

public final class ShowResult extends Command {
    private final Evaluator evaluator;
    private final ConsoleLogger logger;

    public ShowResult(Evaluator evaluator, ConsoleLogger logger) {
        super(null, containsResult(evaluator), null);
        this.evaluator = evaluator;
        this.logger = logger;
    }

    public void execute(String expression) {
        logger.success(evaluator.result(expression).get().toString());
    }

    private static Predicate<String> containsResult(final Evaluator evaluator) {
        return expression -> !evaluator.result(expression).isEmpty();
    }
}
