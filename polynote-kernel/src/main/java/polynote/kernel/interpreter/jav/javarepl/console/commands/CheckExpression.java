package polynote.kernel.interpreter.jav.javarepl.console.commands;

import com.googlecode.totallylazy.Either;
import com.googlecode.totallylazy.functions.Callables;
import polynote.kernel.interpreter.jav.javarepl.Evaluation;
import polynote.kernel.interpreter.jav.javarepl.Evaluator;
import polynote.kernel.interpreter.jav.javarepl.completion.CommandCompleter;
import polynote.kernel.interpreter.jav.javarepl.console.ConsoleLogger;
import polynote.kernel.interpreter.jav.javarepl.expressions.Expression;

import static com.googlecode.totallylazy.Strings.startsWith;
import static java.lang.String.format;

public final class CheckExpression extends Command {
    private static final String COMMAND = ":check";
    private final Evaluator evaluator;
    private final ConsoleLogger logger;

    public CheckExpression(Evaluator evaluator, ConsoleLogger logger) {
        super(COMMAND + " <syntax> - checks syntax of given single line expression and returns detailed report", startsWith(COMMAND), new CommandCompleter(COMMAND));
        this.evaluator = evaluator;
        this.logger = logger;
    }

    public void execute(String expression) {
        String syntaxToCheck = parseStringCommand(expression).second().getOrNull();
        try {
            Either<Throwable, Expression> result = evaluator.parseExpression(syntaxToCheck);

            if (result.isLeft()) {
                logger.error(format("Parsing:  %s", result.left()));
            } else {
                logger.success(format("Parsing:  %s", result.right()));

                Either<Throwable, Evaluation> evaluation = evaluator.tryEvaluate(syntaxToCheck);
                if (evaluation.isLeft()) {
                    logger.error(format("Evaluation:  %s", evaluation.left()));
                } else {
                    logger.success(format("Evaluation:  %s => %s",
                            evaluation.right().expression(),
                            evaluation.right().result().map(Callables.asString()).getOrElse("void")));
                }

            }

        } catch (Exception e) {
            logger.error(format("Could not check syntax %s. %s", syntaxToCheck, e.getLocalizedMessage()));
        }
    }
}
