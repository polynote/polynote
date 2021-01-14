package polynote.kernel.interpreter.jav.javarepl.console.commands;

import polynote.kernel.interpreter.jav.javarepl.Evaluator;
import polynote.kernel.interpreter.jav.javarepl.completion.CommandCompleter;
import polynote.kernel.interpreter.jav.javarepl.console.ConsoleLogger;
import polynote.kernel.interpreter.jav.javarepl.expressions.Import;
import polynote.kernel.interpreter.jav.javarepl.expressions.Method;
import polynote.kernel.interpreter.jav.javarepl.expressions.Type;

import static com.googlecode.totallylazy.Sequences.sequence;
import static com.googlecode.totallylazy.Strings.startsWith;
import static polynote.kernel.interpreter.jav.javarepl.Utils.listValues;

public final class ListValues extends Command {
    private static final String COMMAND = ":list";
    private final Evaluator evaluator;
    private final ConsoleLogger logger;


    public ListValues(Evaluator evaluator, ConsoleLogger logger) {
        super(COMMAND + " <results|types|methods|imports|all> - list specified values",
                startsWith(COMMAND), new CommandCompleter(COMMAND, sequence("results", "methods", "imports", "types", "all")));

        this.evaluator = evaluator;
        this.logger = logger;
    }

    public void execute(String expression) {
        String items = expression.replace(COMMAND, "").trim();

        if (items.equals("results")) {
            listResults();
        } else if (items.equals("types")) {
            listTypes();
        } else if (items.equals("imports")) {
            listImports();
        } else if (items.equals("methods")) {
            listMethods();
        } else {
            listResults();
            listTypes();
            listImports();
            listMethods();
        }
    }

    private void listMethods() {
        logger.success(listValues("Methods", sequence(evaluator.expressionsOfType(Method.class)).map(Method::signature)));
    }

    private void listImports() {
        logger.success(listValues("Imports", sequence(evaluator.expressionsOfType(Import.class)).map(Import::typePackage)));
    }

    private void listTypes() {
        logger.success(listValues("Types", sequence(evaluator.expressionsOfType(Type.class)).map(Type::type)));
    }

    private void listResults() {
        logger.success(listValues("Results", evaluator.results()));
    }
}
