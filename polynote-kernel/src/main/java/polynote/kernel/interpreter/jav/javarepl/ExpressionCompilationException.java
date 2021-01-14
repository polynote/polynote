package polynote.kernel.interpreter.jav.javarepl;

import com.googlecode.totallylazy.functions.Function1;
import com.googlecode.totallylazy.predicates.Predicate;

import javax.tools.Diagnostic;
import java.io.File;
import java.util.Locale;

import static com.googlecode.totallylazy.Sequences.repeat;
import static com.googlecode.totallylazy.Sequences.sequence;
import static com.googlecode.totallylazy.Strings.lines;
import static java.lang.String.format;
import static java.util.regex.Pattern.quote;
import static javax.tools.Diagnostic.Kind.ERROR;

public class ExpressionCompilationException extends Exception {

    public ExpressionCompilationException(File file, Iterable<? extends Diagnostic<?>> diagnostics) {
        this(diagnosticsAsMessage(file, diagnostics));
    }

    public ExpressionCompilationException(String message) {
        super(message);
    }

    private static String diagnosticsAsMessage(final File file, final Iterable<? extends Diagnostic<?>> diagnostics) {
        return sequence(diagnostics)
                .filter(isError())
                .map(diagnosticToMessage(file))
                .toString("\n");
    }

    private static Function1<Diagnostic<?>, String> diagnosticToMessage(final File file) {
        return diagnostic -> {
            String line = lines(file).drop((int) diagnostic.getLineNumber() - 1).head();
            String marker = repeat(' ').take((int) diagnostic.getColumnNumber() - 1).toString("", "", "^");
            String message = diagnostic.getMessage(Locale.getDefault());
            String evaluationClass = file.getName().replaceAll("\\.java", "");
            return format("%s: %s\n%s\n%s", diagnostic.getKind(), message, line, marker)
                    .replaceAll(quote(evaluationClass), "Evaluation");
        };
    }

    private static Predicate<Diagnostic<?>> isError() {
        return diagnostic -> diagnostic.getKind() == ERROR;
    }
}
