package polynote.kernel.interpreter.jav.javarepl.rendering;

public final class ExpressionTemplate {
    private final String template;
    private final String token;

    public ExpressionTemplate(String template, String token) {
        this.template = template;
        this.token = token;
    }

    public String template() {
        return template;
    }

    public String token() {
        return token;
    }
}
