package polynote.kernel.interpreter.jav.javarepl.console;

import static polynote.kernel.interpreter.jav.javarepl.console.ConsoleLog.Type.*;

public class ConsoleLog {
    public static enum Type {
        INFO, SUCCESS, ERROR, CONTROL
    }

    private final Type type;
    private final String message;

    private ConsoleLog(Type type, String message) {
        this.type = type;
        this.message = message;
    }

    public static ConsoleLog control(String message) {
        return consoleLog(CONTROL, message);
    }

    public static ConsoleLog success(String message) {
        return consoleLog(SUCCESS, message);
    }

    public static ConsoleLog error(String message) {
        return consoleLog(ERROR, message);
    }

    public static ConsoleLog info(String message) {
        return consoleLog(INFO, message);
    }

    public static ConsoleLog consoleLog(ConsoleLog.Type type, String message) {
        return new ConsoleLog(type, message);
    }

    public Type type() {
        return type;
    }

    public String message() {
        return message;
    }

    @Override
    public String toString() {
        return type + " " + message;
    }

    @Override
    public int hashCode() {
        return (type != null ? type.hashCode() : 0) +
                (message != null ? message.hashCode() : 0);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ConsoleLog &&
                (type != null && type.equals(((ConsoleLog) other).type)) &&
                (message != null && message.equals(((ConsoleLog) other).message));
    }
}
