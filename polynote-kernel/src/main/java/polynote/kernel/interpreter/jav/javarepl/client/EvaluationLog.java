package polynote.kernel.interpreter.jav.javarepl.client;

public class EvaluationLog {
    public static enum Type {
        INFO, SUCCESS, ERROR, CONTROL;

        public static Type type(String type) {
            return valueOf(type);
        }
    }

    private final Type type;
    private final String message;

    public EvaluationLog(Type type, String message) {
        this.type = type;
        this.message = message;
    }

    public Type type() {
        return type;
    }

    public String message() {
        return message;
    }
}
