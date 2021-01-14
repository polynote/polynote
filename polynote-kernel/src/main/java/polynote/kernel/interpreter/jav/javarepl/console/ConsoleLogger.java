package polynote.kernel.interpreter.jav.javarepl.console;

import com.googlecode.totallylazy.Sequence;
import com.googlecode.totallylazy.Sequences;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;

public final class ConsoleLogger {
    private Sequence<ConsoleLog> logs = Sequences.empty();
    private final PrintStream infoStream;
    private final PrintStream errorStream;
    private final Boolean colored;

    public ConsoleLogger(PrintStream infoStream, PrintStream errorStream, Boolean colored) {
        this.infoStream = infoStream;
        this.errorStream = errorStream;
        this.colored = colored;
    }

    public ConsoleLogger() {
        infoStream = voidOutputStream();
        errorStream = voidOutputStream();
        colored = false;
    }

    public void info(String message) {
        log(ConsoleLog.info(message));
    }

    public void control(String message) {
        log(ConsoleLog.control(message));
    }

    public void success(String message) {
        log(ConsoleLog.success(message));
    }

    public void error(String message) {
        log(ConsoleLog.error(message));
    }

    public void log(ConsoleLog log) {
        switch (log.type()) {
            case INFO:
                printColored(infoStream, log.message(), "\u001B[0m");
                break;
            case SUCCESS:
                printColored(infoStream, log.message(), "\u001B[32m");
                break;
            case ERROR:
                printColored(errorStream, log.message(), "\u001B[31m");
                break;
        }

        logs = logs.append(log);
    }

    private void printColored(PrintStream stream, String message, String color) {
        if (colored)
            stream.print(color);

        stream.println(message);

        if (colored)
            stream.print("\u001B[0m");
    }

    public Sequence<ConsoleLog> logs() {
        return logs;
    }

    public void reset() {
        logs = Sequences.empty();
    }

    private PrintStream voidOutputStream() {
        return new PrintStream(new OutputStream() {
            public void write(int b) throws IOException {
            }
        });
    }
}
