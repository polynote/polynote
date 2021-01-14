package polynote.kernel.interpreter.jav.javarepl.console;

import com.googlecode.totallylazy.predicates.Predicate;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Locale;

import static com.googlecode.totallylazy.predicates.Predicates.not;
import static polynote.kernel.interpreter.jav.javarepl.console.ConsoleLog.consoleLog;

public class ConsoleLoggerPrintStream extends PrintStream {
    private final ConsoleLog.Type type;
    private final Predicate<String> ignore;
    private final ConsoleLogger logger;

    public ConsoleLoggerPrintStream(ConsoleLog.Type type, Predicate<String> ignore, ConsoleLogger logger) {
        super(new OutputStream() {
            public void write(int b) throws IOException {
            }
        });
        this.type = type;
        this.ignore = ignore;
        this.logger = logger;
    }

    @Override
    public void write(byte[] buf, int off, int len) {
        logMessage(new String(buf, off, len));
    }

    @Override
    public void print(boolean b) {
        logMessage(String.valueOf(b));
    }

    @Override
    public void print(char c) {
        logMessage(String.valueOf(c));
    }

    @Override
    public void print(int i) {
        logMessage(String.valueOf(i));
    }

    @Override
    public void print(long l) {
        logMessage(String.valueOf(l));
    }

    @Override
    public void print(float f) {
        logMessage(String.valueOf(f));
    }

    @Override
    public void print(double d) {
        logMessage(String.valueOf(d));
    }

    @Override
    public void print(char[] s) {
        logMessage(String.valueOf(s));
    }

    @Override
    public void print(String s) {
        logMessage(s);
    }

    @Override
    public void print(Object obj) {
        logMessage(String.valueOf(obj));
    }

    @Override
    public void println() {
        logMessage("");
    }

    @Override
    public void println(boolean x) {
        logMessage(String.valueOf(x));
    }

    @Override
    public void println(char x) {
        logMessage(String.valueOf(x));
    }

    @Override
    public void println(int x) {
        logMessage(String.valueOf(x));
    }

    @Override
    public void println(long x) {
        logMessage(String.valueOf(x));
    }

    @Override
    public void println(float x) {
        logMessage(String.valueOf(x));
    }

    @Override
    public void println(double x) {
        logMessage(String.valueOf(x));
    }

    @Override
    public void println(char[] x) {
        logMessage(String.valueOf(x));
    }

    @Override
    public void println(String x) {
        logMessage(String.valueOf(x));
    }

    @Override
    public void println(Object x) {
        logMessage(String.valueOf(x));
    }

    @Override
    public PrintStream printf(String format, Object... args) {
        logMessage(String.format(format, args));
        return this;
    }

    @Override
    public PrintStream printf(Locale l, String format, Object... args) {
        logMessage(String.format(l, format, args));
        return this;
    }

    @Override
    public PrintStream format(String format, Object... args) {
        logMessage(String.format(format, args));
        return this;
    }

    @Override
    public PrintStream format(Locale l, String format, Object... args) {
        logMessage(String.format(l, format, args));
        return this;
    }

    @Override
    public PrintStream append(CharSequence csq) {
        logMessage(String.valueOf(csq));
        return this;
    }

    @Override
    public PrintStream append(CharSequence csq, int start, int end) {
        logMessage(String.valueOf(csq).substring(start, end));
        return this;
    }

    @Override
    public PrintStream append(char c) {
        logMessage(String.valueOf(c));
        return this;
    }

    private void logMessage(String message) {
        if (not(ignore).matches(message))
            logger.log(consoleLog(type, message));
    }
}
