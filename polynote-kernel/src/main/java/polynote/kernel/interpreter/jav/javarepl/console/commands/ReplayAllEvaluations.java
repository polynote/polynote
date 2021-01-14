package polynote.kernel.interpreter.jav.javarepl.console.commands;

import com.googlecode.totallylazy.Sequence;
import polynote.kernel.interpreter.jav.javarepl.Evaluator;
import polynote.kernel.interpreter.jav.javarepl.completion.CommandCompleter;
import polynote.kernel.interpreter.jav.javarepl.console.ConsoleLogger;
import polynote.kernel.interpreter.jav.javarepl.expressions.Expression;

import static com.googlecode.totallylazy.predicates.Predicates.equalTo;
import static polynote.kernel.interpreter.jav.javarepl.EvaluationContext.defaultExpressions;
import static polynote.kernel.interpreter.jav.javarepl.console.commands.EvaluateExpression.evaluate;

public final class ReplayAllEvaluations extends Command {
    private static final String COMMAND = ":replay";
    private final Evaluator evaluator;
    private final ConsoleLogger logger;

    public ReplayAllEvaluations(Evaluator evaluator, ConsoleLogger logger) {
        super(COMMAND + " - replay all evaluations", equalTo(COMMAND), new CommandCompleter(COMMAND));
        this.evaluator = evaluator;
        this.logger = logger;
    }

    public void execute(String line) {
        logger.success("Replaying all evaluations:");
        Sequence<Expression> expressions = evaluator.expressions().deleteAll(defaultExpressions());

        evaluator.reset();
        for (Expression expression : expressions) {
            evaluate(evaluator, logger, expression.source());
        }
    }
}
