package polynote.kernel.interpreter.jav.javarepl.console;

public enum ConsoleStatus {
    Idle, Starting, Running, Terminating, Terminated;

    public static ConsoleStatus consoleStatus(String consoleStatus) {
        return valueOf(consoleStatus);
    }

    public Boolean isRunning() {
        return this == Running;
    }
}
