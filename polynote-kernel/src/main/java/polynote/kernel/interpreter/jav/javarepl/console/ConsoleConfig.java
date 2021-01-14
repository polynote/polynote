package polynote.kernel.interpreter.jav.javarepl.console;


import com.googlecode.totallylazy.Option;
import com.googlecode.totallylazy.Sequence;
import com.googlecode.totallylazy.Sequences;
import polynote.kernel.interpreter.jav.javarepl.Evaluator;
import polynote.kernel.interpreter.jav.javarepl.Result;
import polynote.kernel.interpreter.jav.javarepl.console.commands.*;

import java.io.File;

import static com.googlecode.totallylazy.Option.none;
import static com.googlecode.totallylazy.Option.option;
import static com.googlecode.totallylazy.Sequences.empty;
import static com.googlecode.totallylazy.Sequences.one;
import static com.googlecode.totallylazy.Sequences.sequence;

public final class ConsoleConfig {
    public final Option<File> historyFile;
    public final Sequence<String> expressions;
    public final ConsoleLogger logger;
    public final Sequence<Class<? extends Command>> commands;
    public final Sequence<Result> results;
    public final Boolean sandboxed;


    private ConsoleConfig(Option<File> historyFile, Sequence<String> expressions, ConsoleLogger logger, Sequence<Class<? extends Command>> commands, Sequence<Result> results, Boolean sandboxed) {
        this.historyFile = historyFile;
        this.expressions = expressions;
        this.logger = logger;
        this.commands = commands;
        this.results = results;
        this.sandboxed = sandboxed;
    }

    public static ConsoleConfig consoleConfig() {
        return new ConsoleConfig(none(File.class), empty(String.class), new ConsoleLogger(), defaultCommands(), empty(Result.class), false);
    }

    public ConsoleConfig logger(ConsoleLogger logger) {
        return new ConsoleConfig(historyFile, expressions, option(logger).getOrElse(new ConsoleLogger()), commands, results, sandboxed);
    }

    public ConsoleConfig historyFile(Option<File> file) {
        return new ConsoleConfig(file, expressions, logger, commands, results, sandboxed);
    }

    public ConsoleConfig historyFile(File file) {
        return new ConsoleConfig(option(file), expressions, logger, commands, results, sandboxed);
    }

    public ConsoleConfig expressions(String... expressions) {
        return new ConsoleConfig(historyFile, sequence(expressions), logger, commands, results, sandboxed);
    }

    @SafeVarargs
    @SuppressWarnings("varargs")
    public final ConsoleConfig commands(Class<? extends Command>... cmds) {
        return new ConsoleConfig(historyFile, expressions, logger, sequence(cmds).unsafeCast(), results, sandboxed);
    }

    public ConsoleConfig results(Result... results) {
        return new ConsoleConfig(historyFile, expressions, logger, commands, sequence(results), sandboxed);
    }

    public ConsoleConfig sandboxed(boolean sandboxed) {
        return new ConsoleConfig(historyFile, expressions, logger, commands, results, sandboxed);
    }

    public static Sequence<Class<? extends Command>> defaultCommands() {
        return Sequences.<Class<? extends Command>>sequence()
                .append(ClearScreen.class)
                .append(QuitApplication.class)
                .append(ShowHistory.class)
                .append(SearchHistory.class)
                .append(EvaluateFromHistory.class)
                .append(ResetAllEvaluations.class)
                .append(ReplayAllEvaluations.class)
                .append(EvaluateFile.class)
                .append(AddToClasspath.class)
                .append(LoadSourceFile.class)
                .append(ListValues.class)
                .append(ShowLastSource.class)
                .append(ShowTypeOfExpression.class)
                .append(CheckExpression.class);
    }

}
